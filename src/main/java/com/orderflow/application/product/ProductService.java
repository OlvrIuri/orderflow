package com.orderflow.application.product;

import com.orderflow.domain.product.InvalidProductException;
import com.orderflow.domain.product.Product;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ProductService {
    private final ProductRepository repository;
    private final Clock clock;

    public ProductService(ProductRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
    }

    public Product create(String sku, String name, BigDecimal price) {
        String normalizedSku = validateSku(sku);
        String normalizedName = validateName(name);
        Product product = new Product(
                UUID.randomUUID(), normalizedSku, normalizedName, price, true, Instant.now(clock)
        );
        repository.save(product);
        return product;
    }

    public Optional<Product> findById(UUID id) {
        return repository.findById(Objects.requireNonNull(id));
    }

    public List<Product> list(int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0) {
            throw new InvalidProductException("Use limit entre 1 e 100 e offset não negativo.");
        }
        return repository.findAll(limit, offset);
    }

    public Optional<Product> update(UUID id, String sku, String name, BigDecimal price, Boolean active) {
        Objects.requireNonNull(id);
        String normalizedSku = validateSku(sku);
        String normalizedName = validateName(name);
        if (active == null) {
            throw new InvalidProductException("Ativo é obrigatório.");
        }
        Product previous = repository.findById(id).orElse(null);
        if (previous == null) {
            return Optional.empty();
        }
        Product updated = new Product(id, normalizedSku, normalizedName, price, active, previous.createdAt());
        return repository.update(updated) ? Optional.of(updated) : Optional.empty();
    }

    private static String validateSku(String sku) {
        if (sku == null || sku.isBlank()) {
            throw new InvalidProductException("SKU é obrigatório.");
        }
        String value = sku.trim().toUpperCase(Locale.ROOT);
        if (value.length() > 64) {
            throw new InvalidProductException("SKU deve ter no máximo 64 caracteres.");
        }
        return value;
    }

    private static String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new InvalidProductException("Nome é obrigatório.");
        }
        String value = name.trim();
        if (value.length() > 120) {
            throw new InvalidProductException("Nome deve ter no máximo 120 caracteres.");
        }
        return value;
    }
}
