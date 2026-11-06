package com.orderflow.infrastructure.database;

import com.orderflow.application.stock.StockRepository;
import com.orderflow.config.AppConfig;
import com.orderflow.domain.stock.InvalidStockException;
import com.orderflow.domain.stock.StockCapacityException;
import com.orderflow.domain.stock.StockSnapshot;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class JdbcStockRepository implements StockRepository {
    private final AppConfig config;

    public JdbcStockRepository(AppConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    @Override
    public Optional<StockSnapshot> findByProductId(UUID productId) {
        try (Connection connection = openConnection()) {
            return findByProductId(connection, productId);
        } catch (SQLException e) {
            throw new DataAccessException("Falha ao consultar estoque.", e);
        }
    }

    @Override
    public Optional<StockSnapshot> add(UUID productId, int quantity) {
        validateQuantity(quantity);
        String sql = """
                INSERT INTO inventory (product_id, on_hand, reserved)
                SELECT id, ?, 0 FROM products WHERE id = ?
                ON CONFLICT (product_id) DO UPDATE
                SET on_hand = inventory.on_hand + EXCLUDED.on_hand
                WHERE inventory.on_hand <= 2147483647 - EXCLUDED.on_hand
                RETURNING product_id, on_hand, reserved
                """;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, quantity);
            statement.setObject(2, productId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return Optional.of(readStock(result));
                }
            }
            if (findByProductId(connection, productId).isPresent()) {
                throw new StockCapacityException();
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new DataAccessException("Falha ao adicionar estoque.", e);
        }
    }

    @Override
    public boolean reserve(UUID productId, int quantity) {
        try (Connection connection = openConnection()) {
            return reserve(connection, productId, quantity);
        } catch (SQLException e) {
            throw new DataAccessException("Falha ao reservar estoque.", e);
        }
    }

    // A conexão recebida pertence ao caso de uso: não fechar nem confirmar aqui.
    public boolean reserve(Connection connection, UUID productId, int quantity) {
        validateQuantity(quantity);
        String sql = """
                UPDATE inventory SET reserved = reserved + ?
                WHERE product_id = ? AND on_hand - reserved >= ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, quantity);
            statement.setObject(2, productId);
            statement.setInt(3, quantity);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new DataAccessException("Falha ao reservar estoque.", e);
        }
    }

    @Override
    public boolean release(UUID productId, int quantity) {
        try (Connection connection = openConnection()) {
            return release(connection, productId, quantity);
        } catch (SQLException e) {
            throw new DataAccessException("Falha ao liberar estoque.", e);
        }
    }

    public boolean release(Connection connection, UUID productId, int quantity) {
        validateQuantity(quantity);
        String sql = """
                UPDATE inventory SET reserved = reserved - ?
                WHERE product_id = ? AND reserved >= ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, quantity);
            statement.setObject(2, productId);
            statement.setInt(3, quantity);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new DataAccessException("Falha ao liberar estoque.", e);
        }
    }

    private Optional<StockSnapshot> findByProductId(Connection connection, UUID productId) throws SQLException {
        String sql = """
                SELECT p.id AS product_id, COALESCE(i.on_hand, 0) AS on_hand,
                       COALESCE(i.reserved, 0) AS reserved
                FROM products p LEFT JOIN inventory i ON i.product_id = p.id
                WHERE p.id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, productId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readStock(result)) : Optional.empty();
            }
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(config.databaseUrl(), config.databaseUser(), config.databasePassword());
    }

    private static StockSnapshot readStock(ResultSet result) throws SQLException {
        return new StockSnapshot(result.getObject("product_id", UUID.class),
                result.getInt("on_hand"), result.getInt("reserved"));
    }

    private static void validateQuantity(int quantity) {
        if (quantity <= 0) {
            throw new InvalidStockException("Quantidade deve ser um inteiro positivo.");
        }
    }
}
