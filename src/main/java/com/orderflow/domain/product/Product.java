package com.orderflow.domain.product;

import java.math.BigDecimal;

import java.time.Instant;
import java.util.UUID;

public record Product(UUID id, String sku, String name, BigDecimal price, boolean active, Instant createdAt ){

    public Product{

        if(price==null){
            throw new InvalidProductException("O preco nao deve ser nulo");
        }
        if(price.compareTo(BigDecimal.ZERO) <= 0){
            throw new InvalidProductException("O preco deve ser maior do que 0");
        }
        if(price.scale()>2){
            throw new InvalidProductException("O preco deve ter apenas duas casas decimais");
        }
    }

}
