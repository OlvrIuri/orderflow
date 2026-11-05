package com.orderflow.presentation.product;

import com.orderflow.domain.product.Product;
import java.math.BigDecimal;
import java.util.UUID;

public record ProductResponse(UUID id, String sku, String name, BigDecimal price,
                              boolean active, String createdAt) {
    public static ProductResponse from(Product product) {
        return new ProductResponse(product.id(), product.sku(), product.name(), product.price(),
                product.active(), product.createdAt().toString());
    }
}
