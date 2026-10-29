package com.orderflow.application.customer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.orderflow.domain.customer.InvalidCustomerException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CustomerServiceTest {
    private final CustomerRepository repository = mock(CustomerRepository.class);
    private final CustomerService service = new CustomerService(
            repository, Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC)
    );

    @Test
    void normalizesCustomerBeforeSaving() {
        var customer = service.create("  Ana Silva  ", "  ANA@EXAMPLE.COM  ");

        assertEquals("Ana Silva", customer.name());
        assertEquals("ana@example.com", customer.email());

    
        assertEquals(Instant.parse("2026-01-01T12:00:00Z"), customer.createdAt());
        verify(repository).save(customer);
    }
    @Test 
    void rejectsInvalidLenghtName(){
        assertThrows(InvalidCustomerException.class, () -> service.create("Maximilian Alexandros Valerius Theodoric Emmanuel Ferdinand Bartholomew Sebastian Constantine Leopold Archibald Montgomery Sylvester", "Maximilian@EXample.com"));
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsMissingNameWithoutSaving() {
        assertThrows(InvalidCustomerException.class, () -> service.create("  ", "ana@example.com"));
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsInvalidEmailWithoutSaving() {
        assertThrows(InvalidCustomerException.class, () -> service.create("Ana", "invalid-email"));
        verifyNoInteractions(repository);
    }
}
