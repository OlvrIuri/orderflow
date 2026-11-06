package com.orderflow.application.stock;

import com.orderflow.domain.stock.StockSnapshot;
import java.util.Optional;
import java.util.UUID;

public interface StockRepository {
    Optional<StockSnapshot> findByProductId(UUID productId);

    Optional<StockSnapshot> add(UUID productId, int quantity);

    boolean reserve(UUID productId, int quantity);

    boolean release(UUID productId, int quantity);
}
