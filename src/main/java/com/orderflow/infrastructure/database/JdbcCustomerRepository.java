package com.orderflow.infrastructure.database;

import com.orderflow.application.customer.CustomerRepository;
import com.orderflow.config.AppConfig;
import com.orderflow.domain.customer.Customer;
import com.orderflow.domain.customer.DuplicateEmailException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

public final class JdbcCustomerRepository implements CustomerRepository {
    private final AppConfig config;

    public JdbcCustomerRepository(AppConfig config) {
        this.config = config;
    }

    @Override
    public void save(Customer customer) {
        String sql = "INSERT INTO customers (id, name, email, created_at) VALUES (?, ?, ?, ?)";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, customer.id());
            statement.setString(2, customer.name());
            statement.setString(3, customer.email());
            statement.setTimestamp(4, Timestamp.from(customer.createdAt()));
            statement.executeUpdate();
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new DuplicateEmailException();
            }
            throw new DataAccessException("Falha ao salvar cliente.", e);
        }
    }

    @Override
    public Optional<Customer> findById(UUID id) {
        String sql = "SELECT id, name, email, created_at FROM customers WHERE id = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Customer(
                        result.getObject("id", UUID.class),
                        result.getString("name"),
                        result.getString("email"),
                        result.getTimestamp("created_at").toInstant()
                ));
            }
        } catch (SQLException e) {
            throw new DataAccessException("Falha ao consultar cliente.", e);
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(config.databaseUrl(), config.databaseUser(), config.databasePassword());
    }
}
