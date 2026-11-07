package com.orderflow.application.order;

import com.orderflow.domain.order.Order;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository {
    CreatedOrder create(UUID customerId, List<OrderLineRequest> lines, String key,
                        String requestHash, Instant now, Instant expiresAt);

    Optional<Order> findById(UUID id);

    Optional<Order> cancel(UUID id, Instant now);
}
