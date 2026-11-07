package com.orderflow.infrastructure.database;

import com.orderflow.application.order.CreatedOrder;
import com.orderflow.application.order.OrderLineRequest;
import com.orderflow.application.order.OrderRepository;
import com.orderflow.config.AppConfig;
import com.orderflow.domain.order.Order;
import com.orderflow.domain.order.OrderConflictException;
import com.orderflow.domain.orderitem.OrderItem;
import com.orderflow.domain.order.OrderMissingException;
import com.orderflow.domain.order.OrderStatus;
import com.orderflow.domain.order.OrderTotals;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class JdbcOrderRepository implements OrderRepository {
    private final AppConfig config;
    private final JdbcStockRepository stock;

    public JdbcOrderRepository(AppConfig config, JdbcStockRepository stock) {
        this.config = Objects.requireNonNull(config);
        this.stock = Objects.requireNonNull(stock);
    }

    @Override
    public CreatedOrder create(UUID customerId, List<OrderLineRequest> lines, String key,
                               String requestHash, Instant now, Instant expiresAt) {
        return transaction(connection -> {
            if (!claimKey(connection, key, requestHash)) {
                return new CreatedOrder(loadExistingForKey(connection, key, requestHash), true);
            }
            requireCustomer(connection, customerId);
            List<OrderItem> items = new ArrayList<>();
            // O serviço entrega IDs ordenados. Locks de produto e estoque seguem essa ordem.
            for (OrderLineRequest line : lines) {
                items.add(new OrderItem(line.productId(), line.quantity(),
                        priceOfActiveProduct(connection, line.productId())));
            }
            BigDecimal total = OrderTotals.calculate(items);
            for (OrderItem item : items) {
                if (!stock.reserve(connection, item.productId(), item.quantity())) {
                    throw new OrderConflictException("OUT_OF_STOCK", "Estoque insuficiente.");
                }
            }
            UUID orderId = UUID.randomUUID();
            insertOrder(connection, orderId, customerId, total, now, expiresAt);
            insertItemsAndReservations(connection, orderId, items, expiresAt);
            insertOutboxEvent(connection, orderId, now);
            completeKey(connection, key, orderId);
            return new CreatedOrder(new Order(orderId, customerId, OrderStatus.PENDING_PAYMENT,
                    total, "BRL", expiresAt, now, null, items), false);
        });
    }

    @Override
    public Optional<Order> findById(UUID id) {
        try (Connection connection = openConnection()) {
            return load(connection, id);
        } catch (SQLException e) {
            throw new DataAccessException("Falha ao consultar pedido.", e);
        }
    }

    @Override
    public Optional<Order> cancel(UUID id, Instant now) {
        return transaction(connection -> {
            Optional<Order> current = loadForUpdate(connection, id);
            if (current.isEmpty()) return Optional.empty();
            if (current.get().status() == OrderStatus.CANCELLED) return current;
            if (current.get().status() != OrderStatus.PENDING_PAYMENT) {
                throw new OrderConflictException("ORDER_STATE_CONFLICT", "Pedido não pode ser cancelado.");
            }
            String reservationSql = """
                    UPDATE stock_reservations SET state = 'RELEASED'
                    WHERE order_id = ? AND state = 'ACTIVE'
                    RETURNING product_id, quantity
                    """;
            try (PreparedStatement statement = connection.prepareStatement(reservationSql)) {
                statement.setObject(1, id);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        if (!stock.release(connection, result.getObject("product_id", UUID.class),
                                result.getInt("quantity"))) {
                            throw new DataAccessException("Reserva inconsistente ao cancelar pedido.");
                        }
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE orders SET status = 'CANCELLED', cancelled_at = ? WHERE id = ?")) {
                statement.setTimestamp(1, Timestamp.from(now));
                statement.setObject(2, id);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE outbox_events SET processed_at = ? WHERE aggregate_id = ? AND processed_at IS NULL")) {
                statement.setTimestamp(1, Timestamp.from(now));
                statement.setObject(2, id);
                statement.executeUpdate();
            }
            return load(connection, id);
        });
    }

    private boolean claimKey(Connection connection, String key, String hash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO idempotency_requests (operation, request_key, request_hash)
                VALUES ('CREATE_ORDER', ?, ?) ON CONFLICT (operation, request_key) DO NOTHING
                """)) {
            statement.setString(1, key);
            statement.setString(2, hash);
            return statement.executeUpdate() == 1;
        }
    }

    private Order loadExistingForKey(Connection connection, String key, String hash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT request_hash, resource_id FROM idempotency_requests
                WHERE operation = 'CREATE_ORDER' AND request_key = ? FOR UPDATE
                """)) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !hash.equals(result.getString("request_hash"))) {
                    throw new OrderConflictException("IDEMPOTENCY_CONFLICT",
                            "Chave de idempotência já usada com outro pedido.");
                }
                UUID id = result.getObject("resource_id", UUID.class);
                if (id == null) throw new DataAccessException("Chave de idempotência incompleta.");
                return load(connection, id).orElseThrow(() ->
                        new DataAccessException("Pedido da chave de idempotência ausente."));
            }
        }
    }

    private void requireCustomer(Connection connection, UUID customerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM customers WHERE id = ? FOR KEY SHARE")) {
            statement.setObject(1, customerId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new OrderMissingException("CUSTOMER_NOT_FOUND", "Cliente não encontrado.");
            }
        }
    }

    private BigDecimal priceOfActiveProduct(Connection connection, UUID productId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT price, active FROM products WHERE id = ? FOR SHARE")) {
            statement.setObject(1, productId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new OrderMissingException("PRODUCT_NOT_FOUND", "Produto não encontrado.");
                if (!result.getBoolean("active")) {
                    throw new OrderConflictException("PRODUCT_INACTIVE", "Produto inativo.");
                }
                return result.getBigDecimal("price");
            }
        }
    }

    private void insertOrder(Connection connection, UUID id, UUID customerId, BigDecimal total,
                             Instant now, Instant expiresAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO orders (id, customer_id, status, total, currency, reservation_expires_at, created_at)
                VALUES (?, ?, 'PENDING_PAYMENT', ?, 'BRL', ?, ?)
                """)) {
            statement.setObject(1, id);
            statement.setObject(2, customerId);
            statement.setBigDecimal(3, total);
            statement.setTimestamp(4, Timestamp.from(expiresAt));
            statement.setTimestamp(5, Timestamp.from(now));
            statement.executeUpdate();
        }
    }

    private void insertItemsAndReservations(Connection connection, UUID orderId,
                                            List<OrderItem> items, Instant expiresAt) throws SQLException {
        try (PreparedStatement item = connection.prepareStatement("""
                    INSERT INTO order_items (order_id, product_id, quantity, unit_price) VALUES (?, ?, ?, ?)
                    """);
             PreparedStatement reservation = connection.prepareStatement("""
                    INSERT INTO stock_reservations (order_id, product_id, quantity, state, expires_at)
                    VALUES (?, ?, ?, 'ACTIVE', ?)
                    """)) {
            for (OrderItem line : items) {
                item.setObject(1, orderId);
                item.setObject(2, line.productId());
                item.setInt(3, line.quantity());
                item.setBigDecimal(4, line.unitPrice());
                item.executeUpdate();
                reservation.setObject(1, orderId);
                reservation.setObject(2, line.productId());
                reservation.setInt(3, line.quantity());
                reservation.setTimestamp(4, Timestamp.from(expiresAt));
                reservation.executeUpdate();
            }
        }
    }

    private void insertOutboxEvent(Connection connection, UUID orderId, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO outbox_events (id, event_type, aggregate_id, available_at, created_at)
                VALUES (?, 'PROCESS_ORDER', ?, ?, ?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, orderId);
            statement.setTimestamp(3, Timestamp.from(now));
            statement.setTimestamp(4, Timestamp.from(now));
            statement.executeUpdate();
        }
    }

    private void completeKey(Connection connection, String key, UUID orderId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE idempotency_requests SET resource_id = ?
                WHERE operation = 'CREATE_ORDER' AND request_key = ?
                """)) {
            statement.setObject(1, orderId);
            statement.setString(2, key);
            statement.executeUpdate();
        }
    }

    private Optional<Order> loadForUpdate(Connection connection, UUID id) throws SQLException {
        return load(connection, id, true);
    }

    private Optional<Order> load(Connection connection, UUID id) throws SQLException {
        return load(connection, id, false);
    }

    private Optional<Order> load(Connection connection, UUID id, boolean lock) throws SQLException {
        String sql = """
                SELECT id, customer_id, status, total, currency, reservation_expires_at,
                       created_at, cancelled_at FROM orders WHERE id = ?
                """ + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                Order order = new Order(result.getObject("id", UUID.class),
                        result.getObject("customer_id", UUID.class),
                        OrderStatus.valueOf(result.getString("status")), result.getBigDecimal("total"),
                        result.getString("currency"), result.getTimestamp("reservation_expires_at").toInstant(),
                        result.getTimestamp("created_at").toInstant(),
                        result.getTimestamp("cancelled_at") == null ? null
                                : result.getTimestamp("cancelled_at").toInstant(),
                        loadItems(connection, id));
                return Optional.of(order);
            }
        }
    }

    private List<OrderItem> loadItems(Connection connection, UUID orderId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT product_id, quantity, unit_price FROM order_items
                WHERE order_id = ? ORDER BY product_id
                """)) {
            statement.setObject(1, orderId);
            try (ResultSet result = statement.executeQuery()) {
                List<OrderItem> items = new ArrayList<>();
                while (result.next()) {
                    items.add(new OrderItem(result.getObject("product_id", UUID.class),
                            result.getInt("quantity"), result.getBigDecimal("unit_price")));
                }
                return items;
            }
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(config.databaseUrl(), config.databaseUser(), config.databasePassword());
    }

    private <T> T transaction(SqlWork<T> work) {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                if (e instanceof SQLException sqlError) {
                    throw new DataAccessException("Falha na transação de pedido.", sqlError);
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new DataAccessException("Falha ao abrir conexão de pedido.", e);
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }
}
