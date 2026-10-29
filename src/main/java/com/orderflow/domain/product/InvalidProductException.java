package com.orderflow.domain.product;

public class InvalidProductException extends RuntimeException {
    public InvalidProductException(String message){
        super(message);
    }
}
