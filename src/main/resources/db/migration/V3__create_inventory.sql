CREATE TABLE inventory (
    product_id UUID PRIMARY KEY REFERENCES products(id),
    on_hand INTEGER NOT NULL DEFAULT 0 CHECK (on_hand >= 0),
    reserved INTEGER NOT NULL DEFAULT 0 CHECK (reserved >= 0 AND reserved <= on_hand)
);
