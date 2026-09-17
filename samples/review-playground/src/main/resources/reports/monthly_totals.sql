-- A fixture for reviewing: raw SQL, no Java around it. See EXPECTATIONS.md.

SELECT c.id,
       c.display_name,
       (SELECT COUNT(*) FROM orders o WHERE o.customer_id = c.id) AS order_count,
       (SELECT SUM(o.total_cents) FROM orders o WHERE o.customer_id = c.id) AS spend_cents,
       (SELECT MAX(o.placed_at) FROM orders o WHERE o.customer_id = c.id) AS last_order
FROM customers c
WHERE EXTRACT(YEAR FROM c.created_at) = 2026
  AND c.email LIKE '%@example.com'
ORDER BY spend_cents DESC NULLS LAST;

SELECT i.sku,
       SUM(i.quantity) AS units,
       SUM(i.price_cents * i.quantity) AS revenue_cents
FROM order_items i, orders o
WHERE o.id = i.order_id
  AND o.placed_at >= now() - INTERVAL '1 month'
GROUP BY i.sku
ORDER BY revenue_cents DESC
LIMIT 100;

SELECT DISTINCT a.entity_id
FROM audit_log a
WHERE a.payload ->> 'status' = 'REFUNDED'
  AND a.at > now() - INTERVAL '90 days';
