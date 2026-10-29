package com.orderflow.presentation.customer;

import com.orderflow.domain.customer.Customer;
import java.util.UUID;

public record CustomerResponse(UUID id, String name, String email, String createdAt) {
    public static CustomerResponse from(Customer customer) {
        return new CustomerResponse(
                customer.id(), customer.name(), customer.email(), customer.createdAt().toString()
        );
    }
}
