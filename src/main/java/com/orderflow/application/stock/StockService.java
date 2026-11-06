package com.orderflow.application.stock;

import com.orderflow.domain.stock.InvalidStockException;
import com.orderflow.domain.stock.StockSnapshot;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class StockService {
    private final StockRepository repository;

    public StockService(StockRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public Optional<StockSnapshot> findByProductId(UUID productId) {
        return repository.findByProductId(Objects.requireNonNull(productId));
    }

    public Optional<StockSnapshot> add(UUID productId, Integer quantity) {
        Objects.requireNonNull(productId);
        return repository.add(productId, validQuantity(quantity));
    }

    public boolean reserve(UUID productId, int quantity) {
        return repository.reserve(Objects.requireNonNull(productId), validQuantity(quantity));
    }

    public boolean release(UUID productId, int quantity) {
        return repository.release(Objects.requireNonNull(productId), validQuantity(quantity));
    }

    private static int validQuantity(Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw new InvalidStockException("Quantidade deve ser um inteiro positivo.");
        }
        return quantity;
    }
}
