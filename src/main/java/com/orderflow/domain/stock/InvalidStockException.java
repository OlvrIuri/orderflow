package com.orderflow.domain.stock;

public final class InvalidStockException extends RuntimeException {
    public InvalidStockException(String message) {
        super(message);
    }
}
