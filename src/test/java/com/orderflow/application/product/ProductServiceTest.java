package com.orderflow.application.product;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.orderflow.domain.product.InvalidProductException;
import com.orderflow.domain.product.Product;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductServiceTest {
    private final RecordingRepository repository = new RecordingRepository();
    private final ProductService service = new ProductService(
            repository, Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC)
    );

    @Test
    void normalizesAndSavesActiveProduct() {
        Product product = service.create("  caneca-1  ", "  Caneca azul  ", new BigDecimal("19.90"));

        assertNotNull(product.id());
        assertEquals("CANECA-1", product.sku());
        assertEquals("Caneca azul", product.name());
        assertEquals(new BigDecimal("19.90"), product.price());
        assertTrue(product.active());
        assertEquals(Instant.parse("2026-01-01T12:00:00Z"), product.createdAt());
        assertEquals(List.of(product), repository.saved);
    }

    @Test
    void rejectsMissingSkuBeforeSaving() {
        assertThrows(InvalidProductException.class,
                () -> service.create("  ", "Caneca", new BigDecimal("1.00")));
        assertTrue(repository.saved.isEmpty());
    }

    @Test
    void rejectsLongSkuBeforeSaving() {
        assertThrows(InvalidProductException.class,
                () -> service.create("a".repeat(65), "Caneca", new BigDecimal("1.00")));
        assertTrue(repository.saved.isEmpty());
    }

    @Test
    void rejectsMissingNameBeforeSaving() {
        assertThrows(InvalidProductException.class,
                () -> service.create("CANECA-1", null, new BigDecimal("1.00")));
        assertTrue(repository.saved.isEmpty());
    }

    @Test
    void rejectsLongNameBeforeSaving() {
        assertThrows(InvalidProductException.class,
                () -> service.create("CANECA-1", "a".repeat(121), new BigDecimal("1.00")));
        assertTrue(repository.saved.isEmpty());
    }

    @Test
    void rejectsInvalidPriceBeforeSaving() {
        assertThrows(InvalidProductException.class,
                () -> service.create("CANECA-1", "Caneca", new BigDecimal("1.001")));
        assertTrue(repository.saved.isEmpty());
    }

    private static final class RecordingRepository implements ProductRepository {
        private final List<Product> saved = new ArrayList<>();

        @Override
        public void save(Product product) {
            saved.add(product);
        }
    }
}
