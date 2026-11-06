package com.orderflow.domain.orderitem;

public class InvalidOrderItemException extends RuntimeException {
    public InvalidOrderItemException(String message){ super(message);}
}
