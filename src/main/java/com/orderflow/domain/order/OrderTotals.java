package com.orderflow.domain.order;

import com.orderflow.domain.orderitem.OrderItem;
import java.math.BigDecimal;
import java.util.List;

public final class OrderTotals {
    private static final BigDecimal MAX_TOTAL = new BigDecimal("9999999999999999.99");

    private OrderTotals() {
    }

    public static BigDecimal calculate(List<OrderItem> items) {
        if (items == null || items.isEmpty()) {
            throw new InvalidOrderException("Pedido deve ter ao menos um item.");
        }
        BigDecimal total = items.stream().map(OrderItem::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(MAX_TOTAL) > 0) {
            throw new InvalidOrderException("Total do pedido excede o limite.");
        }
        return total;
    }
}
