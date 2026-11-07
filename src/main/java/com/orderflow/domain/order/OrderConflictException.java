package com.orderflow.domain.order;

public final class OrderConflictException extends RuntimeException {
    private final String code;

    public OrderConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
