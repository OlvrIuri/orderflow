package com.orderflow.infrastructure.database;

import com.orderflow.application.product.ProductRepository;
import com.orderflow.config.AppConfig;
import com.orderflow.domain.product.DuplicateSkuException;
import com.orderflow.domain.product.Product;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

public final class JdbcProductRepository implements ProductRepository {
    private final AppConfig config;

    public JdbcProductRepository(AppConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    @Override
    public void save(Product product) {
        String sql = """
                INSERT INTO products (id, sku, name, price, active, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = DriverManager.getConnection(
                     config.databaseUrl(), config.databaseUser(), config.databasePassword());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, product.id());
            statement.setString(2, product.sku());
            statement.setString(3, product.name());
            statement.setBigDecimal(4, product.price());
            statement.setBoolean(5, product.active());
            statement.setTimestamp(6, Timestamp.from(product.createdAt()));
            statement.executeUpdate();
        } catch (SQLException e) {
            if (isDuplicateSku(e)) {
                throw new DuplicateSkuException();
            }
            throw new DataAccessException("Falha ao salvar produto.", e);
        }
    }

    @Override
    public Optional<Product> findById(UUID id) {
        String sql = "SELECT id, sku, name, price, active, created_at FROM products WHERE id = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readProduct(result)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DataAccessException("Falha ao consultar produto.", e);
        }
    }

    @Override
    public List<Product> findAll(int limit, int offset) {
        String sql = """
                SELECT id, sku, name, price, active, created_at FROM products
                ORDER BY created_at, id LIMIT ? OFFSET ?
                """;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            statement.setInt(2, offset);
            try (ResultSet result = statement.executeQuery()) {
                List<Product> products = new ArrayList<>();
                while (result.next()) {
                    products.add(readProduct(result));
                }
                return products;
            }
        } catch (SQLException e) {
            throw new DataAccessException("Falha ao listar produtos.", e);
        }
    }

    @Override
    public boolean update(Product product) {
        String sql = "UPDATE products SET sku = ?, name = ?, price = ?, active = ? WHERE id = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, product.sku());
            statement.setString(2, product.name());
            statement.setBigDecimal(3, product.price());
            statement.setBoolean(4, product.active());
            statement.setObject(5, product.id());
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            if (isDuplicateSku(e)) {
                throw new DuplicateSkuException();
            }
            throw new DataAccessException("Falha ao atualizar produto.", e);
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(config.databaseUrl(), config.databaseUser(), config.databasePassword());
    }

    private static Product readProduct(ResultSet result) throws SQLException {
        return new Product(
                result.getObject("id", UUID.class),
                result.getString("sku"),
                result.getString("name"),
                result.getBigDecimal("price"),
                result.getBoolean("active"),
                result.getTimestamp("created_at").toInstant()
        );
    }

    private static boolean isDuplicateSku(SQLException error) {
        if (!"23505".equals(error.getSQLState()) || !(error instanceof PSQLException postgresError)) {
            return false;
        }
        ServerErrorMessage serverError = postgresError.getServerErrorMessage();
        return serverError != null && "uq_products_sku".equals(serverError.getConstraint());
    }
}
