package com.orderflow.presentation.order;

import com.orderflow.domain.orderitem.OrderItem;
import java.math.BigDecimal;
import java.util.UUID;

public record OrderItemResponse(UUID productId, int quantity, BigDecimal unitPrice, BigDecimal subtotal) {
    public static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(item.productId(), item.quantity(), item.unitPrice(), item.subtotal());
    }
}
