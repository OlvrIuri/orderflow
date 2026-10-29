package com.orderflow.domain.customer;

public final class DuplicateEmailException extends RuntimeException {
    public DuplicateEmailException() {
        super("E-mail já cadastrado.");
    }
}
