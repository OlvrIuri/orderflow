package com.orderflow.presentation.customer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.application.customer.CustomerRepository;
import com.orderflow.application.customer.CustomerService;
import com.orderflow.domain.customer.Customer;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CustomerHandlerTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private URI baseUrl;

    @BeforeEach
    void startServer() throws Exception {
        Map<UUID, Customer> customers = new ConcurrentHashMap<>();
        CustomerRepository repository = new CustomerRepository() {
            @Override
            public void save(Customer customer) {
                customers.put(customer.id(), customer);
            }

            @Override
            public Optional<Customer> findById(UUID id) {
                return Optional.ofNullable(customers.get(id));
            }
        };
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/customers", new CustomerHandler(
                new CustomerService(repository, Clock.systemUTC()), json
        ));
        server.start();
        baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void createsCustomerAndReturnsItById() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest create = HttpRequest.newBuilder(baseUrl.resolve("/customers"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"Ana\",\"email\":\"ANA@example.com\"}"))
                    .build();
            HttpResponse<String> created = client.send(create, HttpResponse.BodyHandlers.ofString());
            assertEquals(201, created.statusCode());
            String location = created.headers().firstValue("Location").orElseThrow();
            assertTrue(location.startsWith("/customers/"));

            HttpResponse<String> found = client.send(
                    HttpRequest.newBuilder(baseUrl.resolve(location)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            JsonNode body = json.readTree(found.body());
            assertEquals(200, found.statusCode());
            assertEquals("Ana", body.get("name").asText());
            assertEquals("ana@example.com", body.get("email").asText());
        }
    }
    @Test
    void returnsNotFoundForUnknownCustomerId() throws Exception {
    try (HttpClient client = HttpClient.newHttpClient()) {
        HttpRequest request = HttpRequest.newBuilder(
                        baseUrl.resolve("/customers/bcc21651-5bf6-4c2e-9127-c501ae4978fe"))
                .GET()
                .build();

        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(404, response.statusCode());
        assertEquals(
                "CUSTOMER_NOT_FOUND",
                json.readTree(response.body()).get("code").asText()
        );
    }
}

    @Test
    void rejectsMalformedJsonWithoutExposingException() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(baseUrl.resolve("/customers"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{"))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(400, response.statusCode());
            assertEquals("INVALID_JSON", json.readTree(response.body()).get("code").asText());
        }
    }
}
