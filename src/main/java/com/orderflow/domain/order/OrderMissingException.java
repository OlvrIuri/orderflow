package com.orderflow.domain.order;

public final class OrderMissingException extends RuntimeException {
    private final String code;

    public OrderMissingException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
