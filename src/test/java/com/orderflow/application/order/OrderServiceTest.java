package com.orderflow.application.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.orderflow.domain.order.InvalidOrderException;
import com.orderflow.domain.order.Order;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class OrderServiceTest {
    private final OrderRepository repository = Mockito.mock(OrderRepository.class);
    private final OrderService service = new OrderService(repository,
            Clock.fixed(Instant.parse("2026-11-07T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void rejectsInvalidDraftBeforeAccessingDatabase() {
        UUID customer = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        assertThrows(InvalidOrderException.class, () -> service.create(customer, List.of(), "key"));
        assertThrows(InvalidOrderException.class, () -> service.create(customer,
                List.of(new OrderLineRequest(product, 0)), "key"));
        assertThrows(InvalidOrderException.class, () -> service.create(customer,
                List.of(new OrderLineRequest(product, 1), new OrderLineRequest(product, 2)), "key"));
        assertThrows(InvalidOrderException.class, () -> service.create(customer,
                List.of(new OrderLineRequest(product, 1)), null));
        verifyNoInteractions(repository);
    }

    @Test
    void sortsItemsAndUsesStableHashForEquivalentOrder() {
        UUID customer = UUID.randomUUID();
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        when(repository.create(eq(customer), any(), eq("key"), any(), any(), any()))
                .thenReturn(new CreatedOrder(Mockito.mock(Order.class), false));
        service.create(customer, List.of(new OrderLineRequest(second, 2), new OrderLineRequest(first, 1)), "key");
        service.create(customer, List.of(new OrderLineRequest(first, 1), new OrderLineRequest(second, 2)), "key");
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(repository, Mockito.times(2)).create(eq(customer),
                eq(List.of(new OrderLineRequest(first, 1), new OrderLineRequest(second, 2))),
                eq("key"), captor.capture(), eq(Instant.parse("2026-11-07T12:00:00Z")),
                eq(Instant.parse("2026-11-07T12:15:00Z")));
        assertEquals(captor.getAllValues().get(0), captor.getAllValues().get(1));
    }
}
