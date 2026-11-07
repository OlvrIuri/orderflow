CREATE TABLE orders (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL REFERENCES customers(id),
    status VARCHAR(32) NOT NULL CHECK (status IN ('PENDING_PAYMENT', 'CONFIRMED', 'CANCELLED', 'PAYMENT_REVIEW')),
    total NUMERIC(18,2) NOT NULL CHECK (total > 0),
    currency CHAR(3) NOT NULL DEFAULT 'BRL' CHECK (currency = 'BRL'),
    reservation_expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    cancelled_at TIMESTAMPTZ
);

CREATE TABLE order_items (
    order_id UUID NOT NULL REFERENCES orders(id),
    product_id UUID NOT NULL REFERENCES products(id),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(12,2) NOT NULL CHECK (unit_price > 0),
    PRIMARY KEY (order_id, product_id)
);

CREATE TABLE stock_reservations (
    order_id UUID NOT NULL REFERENCES orders(id),
    product_id UUID NOT NULL REFERENCES products(id),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    state VARCHAR(16) NOT NULL CHECK (state IN ('ACTIVE', 'RELEASED', 'CONSUMED')),
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (order_id, product_id),
    FOREIGN KEY (order_id, product_id) REFERENCES order_items(order_id, product_id)
);

CREATE TABLE idempotency_requests (
    operation VARCHAR(32) NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    resource_id UUID REFERENCES orders(id),
    PRIMARY KEY (operation, request_key)
);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL REFERENCES orders(id),
    available_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    locked_until TIMESTAMPTZ,
    processed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_outbox_ready ON outbox_events (available_at, id) WHERE processed_at IS NULL;
