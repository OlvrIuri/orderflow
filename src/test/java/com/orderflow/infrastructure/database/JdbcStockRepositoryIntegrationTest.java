package com.orderflow.infrastructure.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.orderflow.config.AppConfig;
import com.orderflow.domain.product.Product;
import com.orderflow.domain.stock.StockCapacityException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class JdbcStockRepositoryIntegrationTest {
    private String url;
    private String user;
    private String password;
    private JdbcStockRepository stock;
    private JdbcProductRepository products;

    @BeforeEach
    void setUp() {
        url = System.getenv("DB_TEST_URL");
        user = System.getenv().getOrDefault("DB_TEST_USER", "orderflow");
        password = System.getenv("DB_TEST_PASSWORD");
        Flyway.configure().dataSource(url, user, password).load().migrate();
        AppConfig config = new AppConfig(8080, url, user, password);
        stock = new JdbcStockRepository(config);
        products = new JdbcProductRepository(config);
    }

    @Test
    void addsReservesReleasesAndRollsBack() throws SQLException {
        UUID id = createProduct();
        assertEquals(0, stock.findByProductId(id).orElseThrow().available());
        assertTrue(stock.findByProductId(UUID.randomUUID()).isEmpty());
        assertTrue(stock.add(UUID.randomUUID(), 1).isEmpty());
        assertEquals(2, stock.add(id, 2).orElseThrow().available());
        assertTrue(stock.reserve(id, 2));
        assertFalse(stock.reserve(id, 1));
        assertFalse(stock.release(id, 3));
        assertTrue(stock.release(id, 1));
        assertEquals(1, stock.findByProductId(id).orElseThrow().reserved());

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            assertTrue(stock.reserve(connection, id, 1));
            connection.rollback();
        }
        assertEquals(1, stock.findByProductId(id).orElseThrow().reserved());
        assertEquals(Integer.MAX_VALUE, stock.add(id, Integer.MAX_VALUE - 2).orElseThrow().onHand());
        assertThrows(StockCapacityException.class, () -> stock.add(id, 1));
    }

    @Test
    @Timeout(10)
    void twoConnectionsCannotReserveTheLastUnit() throws Exception {
        UUID id = createProduct();
        stock.add(id, 1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection first = openConnection()) {
            first.setAutoCommit(false);
            assertTrue(stock.reserve(first, id, 1));
            CountDownLatch secondStarted = new CountDownLatch(1);
            Future<Boolean> secondResult = executor.submit(() -> {
                try (Connection second = openConnection()) {
                    second.setAutoCommit(false);
                    secondStarted.countDown();
                    boolean reserved = stock.reserve(second, id, 1);
                    second.commit();
                    return reserved;
                }
            });
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> secondResult.get(200, TimeUnit.MILLISECONDS));
            first.commit();
            assertFalse(secondResult.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, stock.findByProductId(id).orElseThrow().reserved());
        assertEquals(0, stock.findByProductId(id).orElseThrow().available());
    }

    @Test
    void databaseEnforcesForeignKeyAndNonnegativeAmounts() throws SQLException {
        UUID id = createProduct();
        try (Connection connection = openConnection()) {
            SQLException missingProduct = assertThrows(SQLException.class,
                    () -> insertInventory(connection, UUID.randomUUID(), 1, 0));
            assertEquals("23503", missingProduct.getSQLState());
            SQLException negative = assertThrows(SQLException.class,
                    () -> insertInventory(connection, id, -1, 0));
            assertEquals("23514", negative.getSQLState());
            SQLException overReserved = assertThrows(SQLException.class,
                    () -> insertInventory(connection, id, 1, 2));
            assertEquals("23514", overReserved.getSQLState());
        }
    }

    private UUID createProduct() {
        UUID id = UUID.randomUUID();
        products.save(new Product(id, "SKU-" + id, "Item", new BigDecimal("1.00"),
                true, Instant.parse("2026-01-01T12:00:00Z")));
        return id;
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    private static void insertInventory(Connection connection, UUID id, int onHand, int reserved)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO inventory (product_id, on_hand, reserved) VALUES (?, ?, ?)")) {
            statement.setObject(1, id);
            statement.setInt(2, onHand);
            statement.setInt(3, reserved);
            statement.executeUpdate();
        }
    }
}
