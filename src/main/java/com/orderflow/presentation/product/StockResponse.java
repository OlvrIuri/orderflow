package com.orderflow.presentation.product;

import com.orderflow.domain.stock.StockSnapshot;
import java.util.UUID;

public record StockResponse(UUID productId, int onHand, int reserved, int available) {
    public static StockResponse from(StockSnapshot stock) {
        return new StockResponse(stock.productId(), stock.onHand(), stock.reserved(), stock.available());
    }
}
