package com.orderflow.domain.orderitem;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderItem(UUID productId, Integer quantity, BigDecimal unitPrice) {
    public OrderItem{
        if(productId==null){
            throw new InvalidOrderItemException("A id nao deve ser nula");
        }
        if(quantity==null || quantity<=0){
            throw new InvalidOrderItemException("A quantidade nao deve ser negativa ou nula");
        }

        if(unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) <= 0){
            throw new InvalidOrderItemException("O preco nao deve ser nulo ou negativo");
        }
        if(unitPrice.scale()>2){
            throw new InvalidOrderItemException("O preco deve ter apenas duas casas decimais");
        }
    }
    public BigDecimal subtotal(){
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
