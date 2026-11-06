package com.orderflow.domain.orderitem;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


class OrderItemTest {
    @Test
    void calculatesSubtotal() {
        OrderItem item = new OrderItem(
                UUID.randomUUID(), 2, new BigDecimal("19.90"));

        assertEquals(new BigDecimal("39.80"), item.subtotal());
    }
    @Test
    void rejectsZeroQuantity() {
        assertThrows(InvalidOrderItemException.class,
                () -> new OrderItem(
                        UUID.randomUUID(), 0, new BigDecimal("19.90")));
    }
    @Test
    void rejectsNegativeQuantity(){
        assertThrows(InvalidOrderItemException.class,
                () -> new OrderItem(
                        UUID.randomUUID(), -1, new BigDecimal("19.90")));
    }
    @Test
    void rejectsNullQuantity(){
        assertThrows(InvalidOrderItemException.class,
                () -> new OrderItem(
                        UUID.randomUUID(), null, new BigDecimal("19.90")));

    }
    @Test
    void rejectsZeroPrice(){
        assertThrows(InvalidOrderItemException.class,
                () -> new OrderItem(
                        UUID.randomUUID(), 2, BigDecimal.ZERO)
        );
    }
    @Test
    void rejectsNegativePrice(){
        assertThrows(InvalidOrderItemException.class,
                () -> new OrderItem(
                        UUID.randomUUID(), 2, new BigDecimal("-1.00"))
                );
    }
    @Test
    void rejectsNullPrice(){
        BigDecimal unitPrice;
        unitPrice = null;
        assertThrows(InvalidOrderItemException.class,
                () -> new OrderItem(
                        UUID.randomUUID(), 2, unitPrice)
        );
    }
    @Test
    void rejectsTwoMoreScale(){
        assertThrows(InvalidOrderItemException.class,
                () -> new OrderItem(
                        UUID.randomUUID(), 2, new BigDecimal("19.000"))
        );
    }
    @Test
    void rejectsNullProduct(){
        assertThrows(InvalidOrderItemException.class,
                () -> new OrderItem(null, 2, new BigDecimal("19.80")));
    }
}
