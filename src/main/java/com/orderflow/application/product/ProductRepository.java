package com.orderflow.application.product;

import com.orderflow.domain.product.Product;

public interface ProductRepository {
    void save(Product product);
}
