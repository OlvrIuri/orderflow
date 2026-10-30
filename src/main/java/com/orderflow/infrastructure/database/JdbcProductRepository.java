package com.orderflow.infrastructure.database;

import com.orderflow.application.product.ProductRepository;
import com.orderflow.config.AppConfig;
import com.orderflow.domain.product.DuplicateSkuException;
import com.orderflow.domain.product.Product;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;
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

    private static boolean isDuplicateSku(SQLException error) {
        if (!"23505".equals(error.getSQLState()) || !(error instanceof PSQLException postgresError)) {
            return false;
        }
        ServerErrorMessage serverError = postgresError.getServerErrorMessage();
        return serverError != null && "uq_products_sku".equals(serverError.getConstraint());
    }
}
