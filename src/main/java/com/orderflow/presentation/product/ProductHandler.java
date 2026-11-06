package com.orderflow.presentation.product;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.application.product.ProductService;
import com.orderflow.application.stock.StockService;
import com.orderflow.domain.product.DuplicateSkuException;
import com.orderflow.domain.product.InvalidProductException;
import com.orderflow.domain.stock.InvalidStockException;
import com.orderflow.domain.stock.StockCapacityException;
import com.orderflow.presentation.ErrorResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ProductHandler implements HttpHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ProductHandler.class);
    private static final int MAX_BODY_BYTES = 16 * 1024;
    private final ProductService service;
    private final StockService stockService;
    private final ObjectMapper json;

    public ProductHandler(ProductService service, StockService stockService, ObjectMapper json) {
        this.service = service;
        this.stockService = stockService;
        this.json = json;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                route(exchange);
            } catch (JsonProcessingException e) {
                respond(exchange, 400, new ErrorResponse("INVALID_JSON", "Corpo JSON inválido."));
            } catch (InvalidProductException e) {
                respond(exchange, 400, new ErrorResponse("INVALID_PRODUCT", e.getMessage()));
            } catch (InvalidStockException e) {
                respond(exchange, 400, new ErrorResponse("INVALID_STOCK", e.getMessage()));
            } catch (StockCapacityException e) {
                respond(exchange, 409, new ErrorResponse("STOCK_LIMIT", e.getMessage()));
            } catch (DuplicateSkuException e) {
                respond(exchange, 409, new ErrorResponse("DUPLICATE_SKU", e.getMessage()));
            } catch (RuntimeException e) {
                LOG.error("Falha inesperada ao processar requisição de produto", e);
                respond(exchange, 500, new ErrorResponse("INTERNAL_ERROR", "Erro interno."));
            }
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String[] segments = path.split("/", -1);
        if (segments.length == 4 && "".equals(segments[0]) && "products".equals(segments[1])
                && !segments[2].isEmpty() && "stock".equals(segments[3])) {
            if ("GET".equals(method)) {
                findStock(exchange, segments[2]);
            } else if ("POST".equals(method)) {
                addStock(exchange, segments[2]);
            } else {
                methodNotAllowed(exchange, "GET, POST");
            }
            return;
        }
        if ("/products".equals(path)) {
            if ("POST".equals(method)) {
                create(exchange);
            } else if ("GET".equals(method)) {
                list(exchange);
            } else {
                methodNotAllowed(exchange, "GET, POST");
            }
            return;
        }
        if (path.startsWith("/products/") && path.indexOf('/', "/products/".length()) == -1) {
            if ("GET".equals(method)) {
                findById(exchange, path.substring("/products/".length()));
            } else if ("PUT".equals(method)) {
                update(exchange, path.substring("/products/".length()));
            } else {
                methodNotAllowed(exchange, "GET, PUT");
            }
            return;
        }
        respond(exchange, 404, new ErrorResponse("NOT_FOUND", "Rota não encontrada."));
    }

    private void create(HttpExchange exchange) throws IOException {
        byte[] body = readBody(exchange);
        if (body == null) return;
        CreateProductRequest request = json.readValue(body, CreateProductRequest.class);
        if (request == null) {
            respond(exchange, 400, new ErrorResponse("INVALID_JSON", "Envie um objeto JSON."));
            return;
        }
        var product = service.create(request.sku(), request.name(), request.price());
        exchange.getResponseHeaders().set("Location", "/products/" + product.id());
        respond(exchange, 201, ProductResponse.from(product));
    }

    private void findById(HttpExchange exchange, String idText) throws IOException {
        UUID id = parseId(exchange, idText);
        if (id == null) return;
        var product = service.findById(id);
        if (product.isEmpty()) {
            productNotFound(exchange);
            return;
        }
        respond(exchange, 200, ProductResponse.from(product.get()));
    }

    private void update(HttpExchange exchange, String idText) throws IOException {
        UUID id = parseId(exchange, idText);
        if (id == null) return;
        byte[] body = readBody(exchange);
        if (body == null) return;
        UpdateProductRequest request = json.readValue(body, UpdateProductRequest.class);
        if (request == null) {
            respond(exchange, 400, new ErrorResponse("INVALID_JSON", "Envie um objeto JSON."));
            return;
        }
        var product = service.update(id, request.sku(), request.name(), request.price(), request.active());
        if (product.isEmpty()) {
            productNotFound(exchange);
            return;
        }
        respond(exchange, 200, ProductResponse.from(product.get()));
    }

    private void list(HttpExchange exchange) throws IOException {
        int limit = 20;
        int offset = 0;
        boolean hasLimit = false;
        boolean hasOffset = false;
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null && !query.isEmpty()) {
            for (String parameter : query.split("&", -1)) {
                String[] pair = parameter.split("=", -1);
                if (pair.length != 2 || pair[1].isEmpty()) {
                    throw new InvalidProductException("Parâmetros de paginação inválidos.");
                }
                try {
                    if ("limit".equals(pair[0]) && !hasLimit) {
                        limit = Integer.parseInt(pair[1]);
                        hasLimit = true;
                    } else if ("offset".equals(pair[0]) && !hasOffset) {
                        offset = Integer.parseInt(pair[1]);
                        hasOffset = true;
                    } else {
                        throw new InvalidProductException("Parâmetros de paginação inválidos.");
                    }
                } catch (NumberFormatException e) {
                    throw new InvalidProductException("Parâmetros de paginação inválidos.");
                }
            }
        }
        List<ProductResponse> items = service.list(limit, offset).stream().map(ProductResponse::from).toList();
        respond(exchange, 200, new ProductListResponse(items, limit, offset));
    }

    private void findStock(HttpExchange exchange, String idText) throws IOException {
        UUID id = parseId(exchange, idText);
        if (id == null) return;
        var stock = stockService.findByProductId(id);
        if (stock.isEmpty()) {
            productNotFound(exchange);
            return;
        }
        respond(exchange, 200, StockResponse.from(stock.get()));
    }

    private void addStock(HttpExchange exchange, String idText) throws IOException {
        UUID id = parseId(exchange, idText);
        if (id == null) return;
        byte[] body = readBody(exchange);
        if (body == null) return;
        StockAdjustmentRequest request = json.readValue(body, StockAdjustmentRequest.class);
        if (request == null) {
            respond(exchange, 400, new ErrorResponse("INVALID_JSON", "Envie um objeto JSON."));
            return;
        }
        var stock = stockService.add(id, request.quantity());
        if (stock.isEmpty()) {
            productNotFound(exchange);
            return;
        }
        respond(exchange, 200, StockResponse.from(stock.get()));
    }

    private byte[] readBody(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
            respond(exchange, 415, new ErrorResponse("UNSUPPORTED_MEDIA_TYPE", "Use Content-Type: application/json."));
            return null;
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            respond(exchange, 413, new ErrorResponse("BODY_TOO_LARGE", "Corpo da requisição excede 16 KiB."));
            return null;
        }
        return body;
    }

    private UUID parseId(HttpExchange exchange, String idText) throws IOException {
        try {
            return UUID.fromString(idText);
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, new ErrorResponse("INVALID_ID", "Identificador de produto inválido."));
            return null;
        }
    }

    private void productNotFound(HttpExchange exchange) throws IOException {
        respond(exchange, 404, new ErrorResponse("PRODUCT_NOT_FOUND", "Produto não encontrado."));
    }

    private void methodNotAllowed(HttpExchange exchange, String allowed) throws IOException {
        exchange.getResponseHeaders().set("Allow", allowed);
        respond(exchange, 405, new ErrorResponse("METHOD_NOT_ALLOWED", "Método HTTP não permitido."));
    }

    private void respond(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
