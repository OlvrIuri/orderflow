package com.orderflow.application.order;

import com.orderflow.domain.order.InvalidOrderException;
import com.orderflow.domain.order.Order;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class OrderService {
    private static final Duration RESERVATION_DURATION = Duration.ofMinutes(15);
    private final OrderRepository repository;
    private final Clock clock;

    public OrderService(OrderRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
    }

    public CreatedOrder create(UUID customerId, List<OrderLineRequest> lines, String key) {
        if (customerId == null || lines == null || lines.isEmpty() || lines.size() > 100) {
            throw new InvalidOrderException("Cliente e de 1 a 100 itens são obrigatórios.");
        }
        if (key == null || key.isBlank() || key.length() > 128 || !key.matches("[\\x21-\\x7E]+")) {
            throw new InvalidOrderException("Idempotency-Key deve ter de 1 a 128 caracteres ASCII visíveis.");
        }
        HashSet<UUID> productIds = new HashSet<>();
        for (OrderLineRequest line : lines) {
            if (line == null || line.productId() == null || line.quantity() == null || line.quantity() <= 0
                    || !productIds.add(line.productId())) {
                throw new InvalidOrderException("Itens devem ter produto distinto e quantidade positiva.");
            }
        }
        List<OrderLineRequest> sorted = lines.stream()
                .sorted(Comparator.comparing(OrderLineRequest::productId)).toList();
        StringBuilder canonical = new StringBuilder(customerId.toString());
        sorted.forEach(line -> canonical.append('|').append(line.productId())
                .append(':').append(line.quantity()));
        Instant now = clock.instant();
        return repository.create(customerId, sorted, key, sha256(canonical.toString()),
                now, now.plus(RESERVATION_DURATION));
    }

    public Optional<Order> findById(UUID id) {
        return repository.findById(Objects.requireNonNull(id));
    }

    public Optional<Order> cancel(UUID id) {
        return repository.cancel(Objects.requireNonNull(id), clock.instant());
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
