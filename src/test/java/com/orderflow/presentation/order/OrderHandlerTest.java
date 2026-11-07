package com.orderflow.presentation.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.application.order.CreatedOrder;
import com.orderflow.application.order.OrderRepository;
import com.orderflow.application.order.OrderService;
import com.orderflow.domain.order.Order;
import com.orderflow.domain.orderitem.OrderItem;
import com.orderflow.domain.order.OrderStatus;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class OrderHandlerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final OrderRepository repository = Mockito.mock(OrderRepository.class);
    private HttpServer server;
    private URI base;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/orders", new OrderHandler(new OrderService(repository,
                Clock.fixed(Instant.parse("2026-11-07T12:00:00Z"), ZoneOffset.UTC)), json));
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void createsReplaysFindsAndCancels() throws Exception {
        UUID customer = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        Order pending = order(id, customer, product, OrderStatus.PENDING_PAYMENT, null);
        Order cancelled = order(id, customer, product, OrderStatus.CANCELLED,
                Instant.parse("2026-11-07T12:01:00Z"));
        when(repository.create(eq(customer), any(), eq("key"), any(), any(), any()))
                .thenReturn(new CreatedOrder(pending, false), new CreatedOrder(pending, true));
        when(repository.findById(id)).thenReturn(Optional.of(pending));
        when(repository.cancel(eq(id), any())).thenReturn(Optional.of(cancelled));
        String body = "{\"customerId\":\"" + customer + "\",\"items\":[{\"productId\":\""
                + product + "\",\"quantity\":2}]}";
        try (HttpClient client = HttpClient.newHttpClient()) {
            var created = post(client, "/orders", body, "key");
            assertEquals(201, created.statusCode());
            assertEquals("/orders/" + id, created.headers().firstValue("Location").orElseThrow());
            assertEquals("PENDING_PAYMENT", json.readTree(created.body()).get("status").asText());
            assertEquals(0, json.readTree(created.body()).get("total").decimalValue()
                    .compareTo(new BigDecimal("39.80")));
            assertEquals(200, post(client, "/orders", body, "key").statusCode());
            assertEquals(200, get(client, "/orders/" + id).statusCode());
            var response = post(client, "/orders/" + id + "/cancel", "", null);
            assertEquals(200, response.statusCode());
            assertEquals("CANCELLED", json.readTree(response.body()).get("status").asText());
        }
    }

    @Test
    void rejectsInvalidRequestsAndMissingOrder() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            UUID customer = UUID.randomUUID();
            UUID product = UUID.randomUUID();
            String valid = "{\"customerId\":\"" + customer + "\",\"items\":[{\"productId\":\""
                    + product + "\",\"quantity\":1}]}";
            var noKey = post(client, "/orders", valid, null);
            assertEquals(400, noKey.statusCode());
            assertEquals("INVALID_ORDER", json.readTree(noKey.body()).get("code").asText());
            assertEquals(400, post(client, "/orders", "{", "key").statusCode());
            assertEquals(400, post(client, "/orders",
                    "{\"customerId\":\"" + customer + "\",\"items\":[]}", "key").statusCode());
            assertEquals(400, get(client, "/orders/not-a-uuid").statusCode());
            assertEquals(404, get(client, "/orders/" + UUID.randomUUID()).statusCode());
            assertEquals(405, get(client, "/orders").statusCode());
            assertTrue(get(client, "/orders").headers().firstValue("Allow").isPresent());
        }
    }

    private Order order(UUID id, UUID customer, UUID product, OrderStatus status, Instant cancelledAt) {
        Instant created = Instant.parse("2026-11-07T12:00:00Z");
        return new Order(id, customer, status, new BigDecimal("39.80"), "BRL",
                created.plusSeconds(900), created, cancelledAt,
                List.of(new OrderItem(product, 2, new BigDecimal("19.90"))));
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(base.resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(HttpClient client, String path, String body, String key) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path));
        if (key != null) builder.header("Idempotency-Key", key);
        if (!body.isEmpty()) builder.header("Content-Type", "application/json");
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
