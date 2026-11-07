package com.orderflow.domain.order;

import com.orderflow.domain.orderitem.OrderItem;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Order(UUID id, UUID customerId, OrderStatus status, BigDecimal total,
                    String currency, Instant reservationExpiresAt, Instant createdAt,
                    Instant cancelledAt, List<OrderItem> items) {
    public Order {
        items = List.copyOf(items);
    }
}
