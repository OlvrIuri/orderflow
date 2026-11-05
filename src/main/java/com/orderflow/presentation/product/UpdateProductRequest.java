package com.orderflow.presentation.product;

import java.math.BigDecimal;

public record UpdateProductRequest(String sku, String name, BigDecimal price, Boolean active) {
}
