package com.orderflow.domain.stock;

public final class StockCapacityException extends RuntimeException {
    public StockCapacityException() {
        super("Quantidade em estoque excede o limite permitido.");
    }
}
