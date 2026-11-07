package com.orderflow.presentation.order;

import com.orderflow.application.order.OrderLineRequest;
import java.util.List;
import java.util.UUID;

public record CreateOrderRequest(UUID customerId, List<OrderLineRequest> items) {
}
