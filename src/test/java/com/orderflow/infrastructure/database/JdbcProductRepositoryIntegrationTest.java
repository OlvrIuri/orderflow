package com.orderflow.infrastructure.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.orderflow.config.AppConfig;
import com.orderflow.domain.product.DuplicateSkuException;
import com.orderflow.domain.product.Product;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class JdbcProductRepositoryIntegrationTest {

    @Test
    void databaseRejectsDuplicateSku() throws SQLException {
        String url = System.getenv("DB_TEST_URL");
        String user = System.getenv().getOrDefault("DB_TEST_USER", "orderflow");
        String password = System.getenv("DB_TEST_PASSWORD");

        Flyway.configure().dataSource(url, user, password).load().migrate();

        String sku = "SKU-" + UUID.randomUUID();

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            insertProduct(connection, UUID.randomUUID(), sku);

            SQLException error = assertThrows(SQLException.class,
                    () -> insertProduct(connection, UUID.randomUUID(), sku));

            assertEquals("23505", error.getSQLState());
        }
    }

    @Test
    void repositoryPersistsProductAndDistinguishesUniqueConflicts() throws SQLException {
        String url = System.getenv("DB_TEST_URL");
        String user = System.getenv().getOrDefault("DB_TEST_USER", "orderflow");
        String password = System.getenv("DB_TEST_PASSWORD");
        Flyway.configure().dataSource(url, user, password).load().migrate();

        JdbcProductRepository repository = new JdbcProductRepository(
                new AppConfig(8080, url, user, password));
        Instant createdAt = Instant.parse("2026-01-01T12:00:00Z");
        String sku = "SKU-" + UUID.randomUUID();
        Product first = new Product(UUID.randomUUID(), sku, "Caneca",
                new BigDecimal("19.90"), true, createdAt);
        Product sameSku = new Product(UUID.randomUUID(), sku, "Outra caneca",
                new BigDecimal("29.90"), true, createdAt);
        Product sameIdDifferentSku = new Product(first.id(), "SKU-" + UUID.randomUUID(), "Terceira caneca",
                new BigDecimal("39.90"), true, createdAt);

        repository.save(first);

        try (Connection connection = DriverManager.getConnection(url, user, password);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT sku, name, price, active, created_at FROM products WHERE id = ?")) {
            statement.setObject(1, first.id());
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(first.sku(), result.getString("sku"));
                assertEquals(first.name(), result.getString("name"));
                assertEquals(first.price(), result.getBigDecimal("price"));
                assertEquals(first.active(), result.getBoolean("active"));
                assertEquals(first.createdAt(), result.getTimestamp("created_at").toInstant());
            }
        }

        assertThrows(DuplicateSkuException.class, () -> repository.save(sameSku));
        assertThrows(DataAccessException.class, () -> repository.save(sameIdDifferentSku));
    }

    @Test
    void repositoryFindsUpdatesAndPagesProducts() {
        String url = System.getenv("DB_TEST_URL");
        String user = System.getenv().getOrDefault("DB_TEST_USER", "orderflow");
        String password = System.getenv("DB_TEST_PASSWORD");
        Flyway.configure().dataSource(url, user, password).load().migrate();
        JdbcProductRepository repository = new JdbcProductRepository(new AppConfig(8080, url, user, password));
        Instant createdAt = Instant.parse("2026-01-01T12:00:00Z");
        Product first = new Product(UUID.randomUUID(), "SKU-" + UUID.randomUUID(), "Primeiro",
                new BigDecimal("19.90"), true, createdAt);
        Product second = new Product(UUID.randomUUID(), "SKU-" + UUID.randomUUID(), "Segundo",
                new BigDecimal("29.90"), true, createdAt);
        repository.save(first);
        repository.save(second);

        assertEquals(first, repository.findById(first.id()).orElseThrow());
        assertTrue(repository.findById(UUID.randomUUID()).isEmpty());
        Product updated = new Product(first.id(), "SKU-" + UUID.randomUUID(), "Atualizado",
                new BigDecimal("39.90"), false, createdAt);
        assertTrue(repository.update(updated));
        assertEquals(updated, repository.findById(first.id()).orElseThrow());
        assertFalse(repository.update(new Product(UUID.randomUUID(), "SKU-" + UUID.randomUUID(),
                "Ausente", new BigDecimal("1.00"), true, createdAt)));
        Product collision = new Product(first.id(), second.sku(), "Conflito",
                new BigDecimal("49.90"), true, createdAt);
        assertThrows(DuplicateSkuException.class, () -> repository.update(collision));
        assertEquals(updated, repository.findById(first.id()).orElseThrow());

        List<Product> page = repository.findAll(2, 0);
        assertEquals(2, page.size());
        assertEquals(page.get(0), repository.findAll(1, 0).get(0));
        assertEquals(page.get(1), repository.findAll(1, 1).get(0));
    }

    private static void insertProduct(Connection connection, UUID id, String sku)
            throws SQLException {
        String sql = """
                INSERT INTO products (id, sku, name, price, active, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            statement.setString(2, sku);
            statement.setString(3, "Caneca");
            statement.setBigDecimal(4, new BigDecimal("19.90"));
            statement.setBoolean(5, true);
            statement.setTimestamp(6, Timestamp.from(Instant.now()));

            statement.executeUpdate();
        }
    }
}
