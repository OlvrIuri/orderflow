package com.orderflow.presentation.order;

import com.orderflow.domain.order.Order;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record OrderResponse(UUID id, UUID customerId, String status, BigDecimal total,
                            String currency, String reservationExpiresAt, String createdAt,
                            String cancelledAt, List<OrderItemResponse> items) {
    public static OrderResponse from(Order order) {
        return new OrderResponse(order.id(), order.customerId(), order.status().name(),
                order.total(), order.currency(), order.reservationExpiresAt().toString(),
                order.createdAt().toString(),
                order.cancelledAt() == null ? null : order.cancelledAt().toString(),
                order.items().stream().map(OrderItemResponse::from).toList());
    }
}
