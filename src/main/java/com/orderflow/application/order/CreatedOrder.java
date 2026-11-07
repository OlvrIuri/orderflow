package com.orderflow.application.order;

import com.orderflow.domain.order.Order;

public record CreatedOrder(Order order, boolean replayed) {
}
