USE datapilot;

CREATE OR REPLACE SQL SECURITY INVOKER VIEW v_paid_order AS
SELECT o.order_id, o.customer_id, o.order_date, o.ship_region_code, o.ship_region_name,
       p.payment_id, p.status AS payment_status, p.paid_at, p.amount AS paid_amount
FROM sales_order o
JOIN payment p ON p.order_id = o.order_id
WHERE p.status IN ('SUCCEEDED', 'PARTIALLY_REFUNDED', 'REFUNDED') AND p.paid_at IS NOT NULL;

CREATE OR REPLACE SQL SECURITY INVOKER VIEW v_refund_by_order AS
SELECT r.order_id, SUM(r.amount) AS refund_amount, MAX(r.refunded_at) AS last_refunded_at
FROM refund r
WHERE r.status = 'COMPLETED'
GROUP BY r.order_id;

CREATE OR REPLACE SQL SECURITY INVOKER VIEW v_refund_by_item AS
SELECT r.order_item_id, SUM(r.quantity) AS refunded_quantity, SUM(r.amount) AS refund_amount,
       MAX(r.refunded_at) AS last_refunded_at
FROM refund r
WHERE r.status = 'COMPLETED'
GROUP BY r.order_item_id;

CREATE OR REPLACE SQL SECURITY INVOKER VIEW v_category_paid_sales AS
SELECT c.category_id, c.category_code, c.category_name,
       COUNT(DISTINCT i.order_id) AS paid_order_count, SUM(i.line_total) AS sales_amount
FROM product_category c
JOIN product p ON p.category_id = c.category_id
JOIN order_item i ON i.product_id = p.product_id
JOIN payment pay ON pay.order_id = i.order_id
WHERE pay.status IN ('SUCCEEDED', 'PARTIALLY_REFUNDED', 'REFUNDED')
GROUP BY c.category_id, c.category_code, c.category_name;

CREATE OR REPLACE SQL SECURITY INVOKER VIEW v_monthly_paid_sales AS
SELECT DATE_FORMAT(p.paid_at, '%Y-%m') AS paid_month,
       COUNT(DISTINCT p.order_id) AS paid_order_count, SUM(p.amount) AS paid_amount
FROM payment p
WHERE p.status IN ('SUCCEEDED', 'PARTIALLY_REFUNDED', 'REFUNDED') AND p.paid_at IS NOT NULL
GROUP BY DATE_FORMAT(p.paid_at, '%Y-%m');

GRANT SHOW VIEW ON datapilot.* TO 'datapilot_reader'@'%';
