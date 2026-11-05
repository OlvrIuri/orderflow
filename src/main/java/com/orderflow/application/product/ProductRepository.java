package com.orderflow.application.product;

import com.orderflow.domain.product.Product;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository {
    void save(Product product);

    Optional<Product> findById(UUID id);

    List<Product> findAll(int limit, int offset);

    boolean update(Product product);
}
