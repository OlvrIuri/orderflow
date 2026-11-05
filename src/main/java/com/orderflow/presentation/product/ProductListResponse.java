package com.orderflow.presentation.product;

import java.util.List;

public record ProductListResponse(List<ProductResponse> items, int limit, int offset) {
}
