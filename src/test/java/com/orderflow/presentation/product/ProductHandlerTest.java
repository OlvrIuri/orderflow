package com.orderflow.presentation.product;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.application.product.ProductRepository;
import com.orderflow.application.product.ProductService;
import com.orderflow.domain.product.DuplicateSkuException;
import com.orderflow.domain.product.Product;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;



class ProductHandlerTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private URI baseUrl;

    @BeforeEach
    void startServer() throws Exception {
        ProductRepository repository = new ProductRepository() {
        Set<String> skus = ConcurrentHashMap.newKeySet();
            @Override
            public void save(Product product) {
                if (!skus.add(product.sku())) {
                    throw new DuplicateSkuException();
                }
            }
            @Override
            public Optional<Product> findById(UUID id) {
                throw new UnsupportedOperationException();
            }
            @Override
            public List<Product> findAll(int limit, int offset) {
                throw new UnsupportedOperationException();
            }
            @Override
            public boolean update(Product product) {
                throw new UnsupportedOperationException();
            }
        };
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/products", new ProductHandler(
                new ProductService(repository, Clock.systemUTC()), json
        ));
        server.start();
        baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }
    @Test
    void returnsConflictAndDuplicateSkuErrorWhenPostingExistingSku() throws Exception{
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest firstRequest = HttpRequest.newBuilder(baseUrl.resolve("/products"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"sku\":\"CANECA-1\",\"name\":\"Caneca\",\"price\":19.90}"))
                    .build();
            HttpResponse<String> firstResponse = client.send(firstRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(201, firstResponse.statusCode());

            HttpRequest secondRequest = HttpRequest.newBuilder(baseUrl.resolve("/products"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"sku\":\"CANECA-1\",\"name\":\"Caneca\",\"price\":19.90}"))
                .build();
            HttpResponse<String> secondResponse =  client.send(secondRequest, HttpResponse.BodyHandlers.ofString());
            String contentType = secondResponse.headers().firstValue("Content-Type").orElse("Não encontrado");
            JsonNode secondBody = json.readTree(secondResponse.body());
            assertEquals(409, secondResponse.statusCode());
            assertEquals("DUPLICATE_SKU", secondBody.get("code").asText());
            assertTrue(secondBody.hasNonNull("message"));
            assertTrue(contentType.startsWith("application/json"));



    }
}
}