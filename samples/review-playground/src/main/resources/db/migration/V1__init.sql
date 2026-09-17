-- The schema the review has to reason against. Column types and nullability matter as
-- much as the indexes: a predicate that compares customer_id to a string cannot use the
-- index on it, and order_items has no index on order_id at all, on purpose.

CREATE TABLE customers (
    id          BIGSERIAL PRIMARY KEY,
    email       VARCHAR(320) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    country     CHAR(2) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE orders (
    id          BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES customers (id),
    status      VARCHAR(20) NOT NULL,
    total_cents BIGINT NOT NULL,
    note        TEXT,
    placed_at   TIMESTAMPTZ NOT NULL
);

CREATE TABLE order_items (
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT NOT NULL,
    sku         VARCHAR(40) NOT NULL,
    quantity    INT NOT NULL,
    price_cents BIGINT NOT NULL
);

CREATE TABLE audit_log (
    id        BIGSERIAL PRIMARY KEY,
    entity    VARCHAR(40) NOT NULL,
    entity_id BIGINT NOT NULL,
    payload   JSONB NOT NULL,
    at        TIMESTAMPTZ NOT NULL
);
