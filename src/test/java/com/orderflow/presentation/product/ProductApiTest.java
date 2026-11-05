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
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProductApiTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private URI baseUrl;

    @BeforeEach
    void startServer() throws Exception {
        ProductRepository repository = new MemoryProducts();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/products", new ProductHandler(
                new ProductService(repository, Clock.systemUTC()), json));
        server.start();
        baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void createsFindsAndUpdatesProduct() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> created = sendJson(client, "POST", "/products",
                    "{\"sku\":\" caneca-1 \",\"name\":\" Caneca \",\"price\":19.90}");
            assertEquals(201, created.statusCode());
            String location = created.headers().firstValue("Location").orElseThrow();
            JsonNode original = json.readTree(created.body());
            assertEquals("CANECA-1", original.get("sku").asText());
            assertEquals("Caneca", original.get("name").asText());
            assertTrue(original.get("active").asBoolean());
            assertEquals(0, original.get("price").decimalValue().compareTo(new BigDecimal("19.90")));

            HttpResponse<String> found = client.send(HttpRequest.newBuilder(baseUrl.resolve(location)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, found.statusCode());
            assertEquals(original.get("id").asText(), json.readTree(found.body()).get("id").asText());

            HttpResponse<String> updated = sendJson(client, "PUT", location,
                    "{\"sku\":\" nova-1 \",\"name\":\" Nova caneca \",\"price\":29.50,\"active\":false}");
            assertEquals(200, updated.statusCode());
            JsonNode changed = json.readTree(updated.body());
            assertEquals("NOVA-1", changed.get("sku").asText());
            assertEquals("Nova caneca", changed.get("name").asText());
            assertEquals(0, changed.get("price").decimalValue().compareTo(new BigDecimal("29.50")));
            assertEquals(false, changed.get("active").asBoolean());
            assertEquals(original.get("id").asText(), changed.get("id").asText());
            assertEquals(original.get("createdAt").asText(), changed.get("createdAt").asText());
        }
    }

    @Test
    void listsProductsInStablePagesAndRejectsInvalidPagination() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            for (int i = 1; i <= 3; i++) {
                assertEquals(201, sendJson(client, "POST", "/products",
                        "{\"sku\":\"SKU-" + i + "\",\"name\":\"Item\",\"price\":1.00}").statusCode());
            }
            JsonNode all = json.readTree(get(client, "/products?limit=3&offset=0").body());
            HttpResponse<String> page = get(client, "/products?limit=1&offset=1");
            assertEquals(200, page.statusCode());
            JsonNode body = json.readTree(page.body());
            assertEquals(1, body.get("limit").asInt());
            assertEquals(1, body.get("offset").asInt());
            assertEquals(1, body.get("items").size());
            assertEquals(all.get("items").get(1).get("id").asText(),
                    body.get("items").get(0).get("id").asText());
            assertEquals(400, get(client, "/products?limit=0").statusCode());
            assertEquals(400, get(client, "/products?limit=101").statusCode());
            assertEquals(400, get(client, "/products?offset=-1").statusCode());
            assertEquals(400, get(client, "/products?limit=abc").statusCode());
        }
    }

    @Test
    void rejectsInvalidUpdateAndMissingProduct() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            String unknown = "/products/" + UUID.randomUUID();
            assertEquals(404, get(client, unknown).statusCode());
            assertEquals(400, get(client, "/products/not-a-uuid").statusCode());
            assertEquals(404, sendJson(client, "PUT", unknown,
                    "{\"sku\":\"SKU-1\",\"name\":\"Item\",\"price\":1.00,\"active\":true}").statusCode());
            HttpResponse<String> created = sendJson(client, "POST", "/products",
                    "{\"sku\":\"SKU-1\",\"name\":\"Item\",\"price\":1.00}");
            String location = created.headers().firstValue("Location").orElseThrow();
            assertEquals(400, sendJson(client, "PUT", location,
                    "{\"sku\":\"SKU-1\",\"name\":\"Item\",\"price\":1.00}").statusCode());
            assertEquals(400, sendJson(client, "PUT", location,
                    "{\"sku\":\"SKU-1\",\"name\":\"Item\",\"price\":1.001,\"active\":true}").statusCode());
        }
    }

    @Test
    void rejectsDuplicateSkuOnUpdate() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> first = sendJson(client, "POST", "/products",
                    "{\"sku\":\"SKU-1\",\"name\":\"Primeiro\",\"price\":1.00}");
            HttpResponse<String> second = sendJson(client, "POST", "/products",
                    "{\"sku\":\"SKU-2\",\"name\":\"Segundo\",\"price\":2.00}");
            String secondLocation = second.headers().firstValue("Location").orElseThrow();
            HttpResponse<String> conflict = sendJson(client, "PUT", secondLocation,
                    "{\"sku\":\"SKU-1\",\"name\":\"Segundo\",\"price\":2.00,\"active\":true}");
            assertEquals(201, first.statusCode());
            assertEquals(409, conflict.statusCode());
            assertEquals("DUPLICATE_SKU", json.readTree(conflict.body()).get("code").asText());
            assertEquals("SKU-2", json.readTree(get(client, secondLocation).body()).get("sku").asText());
        }
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(baseUrl.resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendJson(HttpClient client, String method, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(baseUrl.resolve(path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static final class MemoryProducts implements ProductRepository {
        private final Map<UUID, Product> products = new ConcurrentHashMap<>();
        private final Map<String, UUID> skus = new ConcurrentHashMap<>();

        @Override
        public void save(Product product) {
            if (skus.putIfAbsent(product.sku(), product.id()) != null) {
                throw new DuplicateSkuException();
            }
            products.put(product.id(), product);
        }

        @Override
        public Optional<Product> findById(UUID id) {
            return Optional.ofNullable(products.get(id));
        }

        @Override
        public List<Product> findAll(int limit, int offset) {
            return products.values().stream()
                    .sorted(Comparator.comparing(Product::createdAt).thenComparing(Product::id))
                    .skip(offset).limit(limit).toList();
        }

        @Override
        public boolean update(Product product) {
            Product previous = products.get(product.id());
            if (previous == null) return false;
            if (!previous.sku().equals(product.sku())) {
                if (skus.putIfAbsent(product.sku(), product.id()) != null) {
                    throw new DuplicateSkuException();
                }
                skus.remove(previous.sku(), product.id());
            }
            products.put(product.id(), product);
            return true;
        }
    }
}
