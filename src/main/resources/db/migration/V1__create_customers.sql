CREATE TABLE customers (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL CHECK (length(trim(name)) >= 2),
    email VARCHAR(254) NOT NULL UNIQUE CHECK (email = lower(email)),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
