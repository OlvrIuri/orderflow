package com.orderflow.presentation.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.application.order.OrderService;
import com.orderflow.domain.order.InvalidOrderException;
import com.orderflow.domain.order.OrderConflictException;
import com.orderflow.domain.order.OrderMissingException;
import com.orderflow.presentation.ErrorResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OrderHandler implements HttpHandler {
    private static final Logger LOG = LoggerFactory.getLogger(OrderHandler.class);
    private static final int MAX_BODY_BYTES = 16 * 1024;
    private final OrderService service;
    private final ObjectMapper json;

    public OrderHandler(OrderService service, ObjectMapper json) {
        this.service = service;
        this.json = json;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                route(exchange);
            } catch (JsonProcessingException e) {
                respond(exchange, 400, new ErrorResponse("INVALID_JSON", "Corpo JSON inválido."));
            } catch (InvalidOrderException e) {
                respond(exchange, 400, new ErrorResponse("INVALID_ORDER", e.getMessage()));
            } catch (OrderMissingException e) {
                respond(exchange, 404, new ErrorResponse(e.code(), e.getMessage()));
            } catch (OrderConflictException e) {
                respond(exchange, 409, new ErrorResponse(e.code(), e.getMessage()));
            } catch (RuntimeException e) {
                LOG.error("Falha inesperada ao processar pedido", e);
                respond(exchange, 500, new ErrorResponse("INTERNAL_ERROR", "Erro interno."));
            }
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if ("/orders".equals(path)) {
            if ("POST".equals(method)) create(exchange);
            else methodNotAllowed(exchange, "POST");
            return;
        }
        String[] segments = path.split("/", -1);
        if (segments.length == 4 && "".equals(segments[0]) && "orders".equals(segments[1])
                && "cancel".equals(segments[3]) && !segments[2].isEmpty()) {
            if ("POST".equals(method)) cancel(exchange, segments[2]);
            else methodNotAllowed(exchange, "POST");
            return;
        }
        if (segments.length == 3 && "".equals(segments[0]) && "orders".equals(segments[1])
                && !segments[2].isEmpty()) {
            if ("GET".equals(method)) findById(exchange, segments[2]);
            else methodNotAllowed(exchange, "GET");
            return;
        }
        respond(exchange, 404, new ErrorResponse("NOT_FOUND", "Rota não encontrada."));
    }

    private void create(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
            respond(exchange, 415, new ErrorResponse("UNSUPPORTED_MEDIA_TYPE", "Use Content-Type: application/json."));
            return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            respond(exchange, 413, new ErrorResponse("BODY_TOO_LARGE", "Corpo da requisição excede 16 KiB."));
            return;
        }
        CreateOrderRequest request = json.readValue(body, CreateOrderRequest.class);
        if (request == null) {
            respond(exchange, 400, new ErrorResponse("INVALID_JSON", "Envie um objeto JSON."));
            return;
        }
        var created = service.create(request.customerId(), request.items(),
                exchange.getRequestHeaders().getFirst("Idempotency-Key"));
        exchange.getResponseHeaders().set("Location", "/orders/" + created.order().id());
        respond(exchange, created.replayed() ? 200 : 201, OrderResponse.from(created.order()));
    }

    private void findById(HttpExchange exchange, String idText) throws IOException {
        UUID id = parseId(exchange, idText);
        if (id == null) return;
        var order = service.findById(id);
        if (order.isEmpty()) {
            respond(exchange, 404, new ErrorResponse("ORDER_NOT_FOUND", "Pedido não encontrado."));
            return;
        }
        respond(exchange, 200, OrderResponse.from(order.get()));
    }

    private void cancel(HttpExchange exchange, String idText) throws IOException {
        UUID id = parseId(exchange, idText);
        if (id == null) return;
        var order = service.cancel(id);
        if (order.isEmpty()) {
            respond(exchange, 404, new ErrorResponse("ORDER_NOT_FOUND", "Pedido não encontrado."));
            return;
        }
        respond(exchange, 200, OrderResponse.from(order.get()));
    }

    private UUID parseId(HttpExchange exchange, String idText) throws IOException {
        try {
            return UUID.fromString(idText);
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, new ErrorResponse("INVALID_ID", "Identificador de pedido inválido."));
            return null;
        }
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
