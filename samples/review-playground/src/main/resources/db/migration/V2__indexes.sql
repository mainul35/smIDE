-- What exists. What does not exist is as much of the exercise: there is no index on
-- order_items.order_id, none on orders.status, and the one on customers.email is on the
-- column as stored, so a query that lowercases the column cannot use it.

CREATE UNIQUE INDEX idx_customers_email ON customers (email);

CREATE INDEX idx_orders_customer_placed ON orders (customer_id, placed_at DESC);

CREATE INDEX idx_audit_entity ON audit_log (entity, entity_id);
