package com.orderflow.infrastructure.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.orderflow.config.AppConfig;
import com.orderflow.domain.customer.Customer;
import com.orderflow.domain.customer.DuplicateEmailException;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class JdbcCustomerRepositoryIntegrationTest {
    @Test
    void databasePersistsCustomerAndRejectsDuplicateEmail() {
        String url = System.getenv("DB_TEST_URL");
        String user = System.getenv().getOrDefault("DB_TEST_USER", "orderflow");
        String password = System.getenv("DB_TEST_PASSWORD");
        AppConfig config = new AppConfig(8080, url, user, password);
        Flyway.configure().dataSource(url, user, password).load().migrate();
        JdbcCustomerRepository repository = new JdbcCustomerRepository(config);
        String email = "test-" + UUID.randomUUID() + "@example.com";
        Customer first = new Customer(UUID.randomUUID(), "Ana Silva", email, Instant.now());
        Customer duplicate = new Customer(UUID.randomUUID(), "Outra Pessoa", email, Instant.now());

        repository.save(first);

        assertEquals(first.id(), repository.findById(first.id()).orElseThrow().id());
        assertThrows(DuplicateEmailException.class, () -> repository.save(duplicate));
    }
}
