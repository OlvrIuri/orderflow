package com.orderflow.application.order;

import java.util.UUID;

public record OrderLineRequest(UUID productId, Integer quantity) {
}
