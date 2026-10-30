package com.orderflow.domain.product;

public final class DuplicateSkuException extends RuntimeException {
    public DuplicateSkuException() {
        super("SKU já cadastrado.");
    }
}
