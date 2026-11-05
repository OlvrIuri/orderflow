package com.orderflow.presentation.product;

import java.math.BigDecimal;

public record CreateProductRequest(String sku, String name, BigDecimal price) {
}
