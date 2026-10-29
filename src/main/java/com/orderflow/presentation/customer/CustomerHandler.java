package com.orderflow.presentation.customer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.application.customer.CustomerService;
import com.orderflow.domain.customer.Customer;
import com.orderflow.domain.customer.DuplicateEmailException;
import com.orderflow.domain.customer.InvalidCustomerException;
import com.orderflow.presentation.ErrorResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CustomerHandler implements HttpHandler {
    private static final Logger LOG = LoggerFactory.getLogger(CustomerHandler.class);
    private static final int MAX_BODY_BYTES = 16 * 1024;

    private final CustomerService service;
    private final ObjectMapper json;

    public CustomerHandler(CustomerService service, ObjectMapper json) {
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
            } catch (InvalidCustomerException e) {
                respond(exchange, 400, new ErrorResponse("INVALID_CUSTOMER", e.getMessage()));
            } catch (DuplicateEmailException e) {
                respond(exchange, 409, new ErrorResponse("EMAIL_ALREADY_EXISTS", e.getMessage()));
            } catch (RuntimeException e) {
                LOG.error("Falha inesperada ao processar requisição de cliente", e);
                respond(exchange, 500, new ErrorResponse("INTERNAL_ERROR", "Erro interno."));
            }
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        if ("/customers".equals(path)) {
            if ("POST".equals(method)) {
                create(exchange);
            } else {
                methodNotAllowed(exchange, "POST");
            }
            return;
        }

        if (path.startsWith("/customers/") && path.indexOf('/', "/customers/".length()) == -1) {
            if ("GET".equals(method)) {
                findById(exchange, path.substring("/customers/".length()));
            } else {
                methodNotAllowed(exchange, "GET");
            }
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

        CreateCustomerRequest request = json.readValue(body, CreateCustomerRequest.class);
        if (request == null) {
            respond(exchange, 400, new ErrorResponse("INVALID_JSON", "Envie um objeto JSON."));
            return;
        }
        Customer customer = service.create(request.name(), request.email());
        exchange.getResponseHeaders().set("Location", "/customers/" + customer.id());
        respond(exchange, 201, CustomerResponse.from(customer));
    }

    private void findById(HttpExchange exchange, String idText) throws IOException {
        UUID id;
        try {
            id = UUID.fromString(idText);
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, new ErrorResponse("INVALID_ID", "Identificador de cliente inválido."));
            return;
        }

        var customer = service.findById(id);
        if (customer.isEmpty()) {
            respond(exchange, 404, new ErrorResponse("CUSTOMER_NOT_FOUND", "Cliente não encontrado."));
            return;
        }
        respond(exchange, 200, CustomerResponse.from(customer.get()));
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
