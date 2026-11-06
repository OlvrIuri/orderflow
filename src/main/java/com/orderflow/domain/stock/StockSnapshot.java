package com.orderflow.domain.stock;

import java.util.UUID;

public record StockSnapshot(UUID productId, int onHand, int reserved) {
    public StockSnapshot {
        if (productId == null || onHand < 0 || reserved < 0 || reserved > onHand) {
            throw new IllegalArgumentException("Estoque inconsistente.");
        }
    }

    public int available() {
        return onHand - reserved;
    }
}
