package com.orderflow.application.customer;

import com.orderflow.domain.customer.Customer;
import com.orderflow.domain.customer.InvalidCustomerException;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public final class CustomerService {
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final CustomerRepository repository;
    private final Clock clock;

    public CustomerService(CustomerRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
    }

    public Customer create(String name, String email) {
        String normalizedName = validateName(name);
        String normalizedEmail = validateEmail(email);
        Customer customer = new Customer(UUID.randomUUID(), normalizedName, normalizedEmail, Instant.now(clock));
        repository.save(customer);
        return customer;
    }

    public Optional<Customer> findById(UUID id) {
        return repository.findById(Objects.requireNonNull(id));
    }

    private static String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new InvalidCustomerException("Nome é obrigatório.");
        }
        String value = name.trim();
        if (value.length() < 2 || value.length() > 120) {
            throw new InvalidCustomerException("Nome deve ter entre 2 e 120 caracteres.");
        }
        return value;
    }

    private static String validateEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new InvalidCustomerException("E-mail é obrigatório.");
        }
        String value = email.trim().toLowerCase(Locale.ROOT);
        if (value.length() > 254 || !EMAIL_PATTERN.matcher(value).matches()) {
            throw new InvalidCustomerException("E-mail inválido.");
        }
        return value;
    }
}
