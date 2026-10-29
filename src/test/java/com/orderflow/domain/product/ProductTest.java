package com.orderflow.domain.product;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductTest {
    private Product productWithPrice(BigDecimal price) {
        return new Product(
                UUID.randomUUID(), "SKU-1", "Caneca",
                price, true, Instant.EPOCH
        );
    }

    @Test
    void acceptsMinimumPositivePrice() {
        Product product = productWithPrice(new BigDecimal("0.01"));
        assertEquals(new BigDecimal("0.01"), product.price());
    }
    @Test
    void acceptsPriceWithTwoDecimalPlaces(){
        Product product = productWithPrice(new BigDecimal("1.20"));
        assertEquals(new BigDecimal("1.20"), product.price());
    }
    @Test
    void acceptsOnePositive(){
        Product product = productWithPrice(new BigDecimal("1"));
        assertEquals(new BigDecimal("1"), product.price());
    }

    @Test
    void rejectsZeroPrice() {
        assertThrows(InvalidProductException.class,
                () -> productWithPrice(BigDecimal.ZERO));
    }
    @Test
    void rejectsNullPrice(){
        assertThrows(InvalidProductException.class, () -> productWithPrice(null));
    }
    @Test
    void rejectsNegativePrice(){
        assertThrows(InvalidProductException.class, () -> productWithPrice(new BigDecimal("-0.01")));
    }
    @Test
    void rejectsThreeDigitsOne(){
        assertThrows(InvalidProductException.class, () -> productWithPrice(new BigDecimal("1.001")));
    }
    @Test
    void rejectsThreeDigitsTwo(){
        assertThrows(InvalidProductException.class, ()-> productWithPrice(new BigDecimal("1.000")));
    }
}
