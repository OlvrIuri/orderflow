package com.orderflow.application.customer;

import com.orderflow.domain.customer.Customer;
import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository {
    void save(Customer customer);

    Optional<Customer> findById(UUID id);
}
