package com.orderflow.infrastructure.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.orderflow.application.order.OrderLineRequest;
import com.orderflow.application.order.OrderService;
import com.orderflow.config.AppConfig;
import com.orderflow.domain.order.OrderConflictException;
import com.orderflow.domain.order.OrderMissingException;
import com.orderflow.domain.order.OrderStatus;
import com.orderflow.domain.product.Product;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class JdbcOrderRepositoryIntegrationTest {
    private String url;
    private String user;
    private String password;
    private JdbcStockRepository stock;
    private JdbcProductRepository products;
    private OrderService orders;

    @BeforeEach
    void setUp() {
        url = System.getenv("DB_TEST_URL");
        user = System.getenv().getOrDefault("DB_TEST_USER", "orderflow");
        password = System.getenv("DB_TEST_PASSWORD");
        Flyway.configure().dataSource(url, user, password).load().migrate();
        AppConfig config = new AppConfig(8080, url, user, password);
        stock = new JdbcStockRepository(config);
        products = new JdbcProductRepository(config);
        orders = new OrderService(new JdbcOrderRepository(config, stock), Clock.systemUTC());
    }

    @Test
    void createsMultiItemOrderWithPriceSnapshotAndIdempotentCancellation() throws SQLException {
        UUID customer = createCustomer();
        UUID first = createProduct("19.90", true);
        UUID second = createProduct("2.35", true);
        stock.add(first, 5);
        stock.add(second, 10);
        String key = UUID.randomUUID().toString();
        List<OrderLineRequest> lines = List.of(new OrderLineRequest(first, 2), new OrderLineRequest(second, 3));
        var created = orders.create(customer, lines, key);
        assertFalse(created.replayed());
        assertEquals(OrderStatus.PENDING_PAYMENT, created.order().status());
        assertEquals(0, created.order().total().compareTo(new BigDecimal("46.85")));
        assertEquals(2, created.order().items().size());
        assertEquals(2, stock.findByProductId(first).orElseThrow().reserved());
        assertEquals(3, stock.findByProductId(second).orElseThrow().reserved());
        assertEquals(1, count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", created.order().id()));

        Product original = products.findById(first).orElseThrow();
        products.update(new Product(first, original.sku(), original.name(), new BigDecimal("99.99"),
                true, original.createdAt()));
        var found = orders.findById(created.order().id()).orElseThrow();
        assertEquals(0, found.total().compareTo(new BigDecimal("46.85")));
        assertEquals(0, found.items().stream().filter(i -> i.productId().equals(first))
                .findFirst().orElseThrow().unitPrice().compareTo(new BigDecimal("19.90")));

        var replay = orders.create(customer, List.of(lines.get(1), lines.get(0)), key);
        assertTrue(replay.replayed());
        assertEquals(created.order().id(), replay.order().id());
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(OrderConflictException.class,
                () -> orders.create(customer, List.of(new OrderLineRequest(first, 1)), key)).code());
        assertEquals(2, stock.findByProductId(first).orElseThrow().reserved());

        var cancelled = orders.cancel(created.order().id()).orElseThrow();
        assertEquals(OrderStatus.CANCELLED, cancelled.status());
        assertEquals(cancelled.cancelledAt(), orders.cancel(created.order().id()).orElseThrow().cancelledAt());
        assertEquals(0, stock.findByProductId(first).orElseThrow().reserved());
        assertEquals(0, stock.findByProductId(second).orElseThrow().reserved());
        assertEquals(2, count("SELECT count(*) FROM stock_reservations WHERE order_id = ? AND state = 'RELEASED'",
                created.order().id()));
        assertEquals(0, count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND processed_at IS NULL",
                created.order().id()));
    }

    @Test
    void rollsBackAllRowsAndStockWhenLaterItemIsShort() throws SQLException {
        UUID customer = createCustomer();
        UUID first = createProduct("3.00", true);
        UUID second = createProduct("4.00", true);
        // UUIDs em ordem para garantir que a primeira reserva ocorra antes da falha.
        UUID available = first.compareTo(second) < 0 ? first : second;
        UUID missing = first.compareTo(second) < 0 ? second : first;
        stock.add(available, 1);
        String key = UUID.randomUUID().toString();
        assertEquals("OUT_OF_STOCK", assertThrows(OrderConflictException.class,
                () -> orders.create(customer, List.of(new OrderLineRequest(available, 1),
                        new OrderLineRequest(missing, 1)), key)).code());
        assertEquals(0, stock.findByProductId(available).orElseThrow().reserved());
        assertEquals(0, countKey(key));
        assertEquals(0, count("SELECT count(*) FROM orders WHERE customer_id = ?", customer));
    }

    @Test
    void rejectsMissingOrInactiveProductsBeforeReservation() throws SQLException {
        UUID customer = createCustomer();
        UUID inactive = createProduct("1.00", false);
        assertEquals("PRODUCT_INACTIVE", assertThrows(OrderConflictException.class,
                () -> orders.create(customer, List.of(new OrderLineRequest(inactive, 1)), UUID.randomUUID().toString()))
                .code());
        assertEquals("PRODUCT_NOT_FOUND", assertThrows(OrderMissingException.class,
                () -> orders.create(customer, List.of(new OrderLineRequest(UUID.randomUUID(), 1)),
                        UUID.randomUUID().toString())).code());
    }

    @Test
    @Timeout(15)
    void concurrentOrdersCannotBothReserveLastUnit() throws Exception {
        UUID customer = createCustomer();
        UUID product = createProduct("1.00", true);
        stock.add(product, 1);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var task = (java.util.concurrent.Callable<Boolean>) () -> {
                start.await();
                try {
                    orders.create(customer, List.of(new OrderLineRequest(product, 1)), UUID.randomUUID().toString());
                    return true;
                } catch (OrderConflictException e) {
                    assertEquals("OUT_OF_STOCK", e.code());
                    return false;
                }
            };
            var first = executor.submit(task);
            var second = executor.submit(task);
            start.countDown();
            assertTrue(first.get(10, TimeUnit.SECONDS) ^ second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, stock.findByProductId(product).orElseThrow().reserved());
    }

    @Test
    @Timeout(15)
    void concurrentRequestsWithSameKeyCreateOnlyOneOrder() throws Exception {
        UUID customer = createCustomer();
        UUID product = createProduct("1.00", true);
        stock.add(product, 2);
        String key = UUID.randomUUID().toString();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var task = (java.util.concurrent.Callable<com.orderflow.application.order.CreatedOrder>) () -> {
                start.await();
                return orders.create(customer, List.of(new OrderLineRequest(product, 1)), key);
            };
            var first = executor.submit(task);
            var second = executor.submit(task);
            start.countDown();
            var a = first.get(10, TimeUnit.SECONDS);
            var b = second.get(10, TimeUnit.SECONDS);
            assertEquals(a.order().id(), b.order().id());
            assertTrue(a.replayed() ^ b.replayed());
            assertEquals(1, countKey(key));
            assertEquals(1, stock.findByProductId(product).orElseThrow().reserved());
        }
    }

    @Test
    void cancellingOneOrderKeepsTheOtherOrdersReservation() throws SQLException {
        UUID customer = createCustomer();
        UUID product = createProduct("1.00", true);
        stock.add(product, 2);
        var first = orders.create(customer, List.of(new OrderLineRequest(product, 1)), UUID.randomUUID().toString());
        var second = orders.create(customer, List.of(new OrderLineRequest(product, 1)), UUID.randomUUID().toString());
        orders.cancel(first.order().id());
        assertEquals(1, stock.findByProductId(product).orElseThrow().reserved());
        assertEquals(1, count("SELECT count(*) FROM stock_reservations WHERE order_id = ? AND state = 'ACTIVE'",
                second.order().id()));
        assertEquals(OrderStatus.PENDING_PAYMENT, orders.findById(second.order().id()).orElseThrow().status());
    }

    private UUID createCustomer() throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO customers (id, name, email, created_at) VALUES (?, 'Buyer', ?, now())")) {
            statement.setObject(1, id);
            statement.setString(2, id + "@example.com");
            statement.executeUpdate();
        }
        return id;
    }

    private UUID createProduct(String price, boolean active) {
        UUID id = UUID.randomUUID();
        products.save(new Product(id, "SKU-" + id, "Item", new BigDecimal(price), active, Instant.now()));
        return id;
    }

    private int countKey(String key) throws SQLException {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM idempotency_requests WHERE request_key = ?")) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private int count(String sql, UUID id) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }
}
