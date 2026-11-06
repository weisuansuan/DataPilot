WITH order_lines AS (
  SELECT order_id, SUM(line_total) AS line_total
  FROM order_item
  GROUP BY order_id
),
refund_item_totals AS (
  SELECT order_item_id, SUM(quantity) AS refunded_quantity, SUM(amount) AS refunded_amount
  FROM refund
  WHERE status = 'COMPLETED'
  GROUP BY order_item_id
),
refund_order_totals AS (
  SELECT order_id, SUM(amount) AS refunded_amount
  FROM refund
  WHERE status = 'COMPLETED'
  GROUP BY order_id
),
region_sales AS (
  SELECT o.ship_region_code,
         SUM(CASE WHEN p.paid_at >= '2026-09-01' AND p.paid_at < '2026-10-01' THEN p.amount ELSE 0 END) AS september_sales,
         SUM(CASE WHEN p.paid_at >= '2026-10-01' AND p.paid_at < '2026-11-01' THEN p.amount ELSE 0 END) AS october_sales
  FROM sales_order o
  JOIN payment p ON p.order_id = o.order_id
  WHERE p.status IN ('SUCCEEDED', 'PARTIALLY_REFUNDED', 'REFUNDED')
    AND p.paid_at >= '2026-09-01' AND p.paid_at < '2026-11-01'
  GROUP BY o.ship_region_code
),
category_refund_rates AS (
  SELECT c.category_code,
         SUM(i.quantity) AS sold_units,
         SUM(COALESCE(r.refunded_quantity, 0)) AS refunded_units
  FROM product_category c
  JOIN product p ON p.category_id = c.category_id
  JOIN order_item i ON i.product_id = p.product_id
  JOIN payment pay ON pay.order_id = i.order_id
    AND pay.status IN ('SUCCEEDED', 'PARTIALLY_REFUNDED', 'REFUNDED')
  LEFT JOIN refund_item_totals r ON r.order_item_id = i.order_item_id
  GROUP BY c.category_code
),
checks AS (
  SELECT 'metadata matches order count' AS check_name,
         IF((SELECT order_count FROM dataset_metadata WHERE dataset_code = 'SYNTHETIC_ECOMMERCE_V1') = (SELECT COUNT(*) FROM sales_order), 'PASS', 'FAIL') AS result,
         CONCAT('metadata=', (SELECT order_count FROM dataset_metadata WHERE dataset_code = 'SYNTHETIC_ECOMMERCE_V1'), ', rows=', (SELECT COUNT(*) FROM sales_order)) AS observed
  UNION ALL
  SELECT 'order total equals item totals',
         IF((SELECT COUNT(*) FROM sales_order o LEFT JOIN order_lines l ON l.order_id = o.order_id WHERE o.total_amount <> COALESCE(l.line_total, 0)) = 0, 'PASS', 'FAIL'),
         CONCAT('mismatched_orders=', (SELECT COUNT(*) FROM sales_order o LEFT JOIN order_lines l ON l.order_id = o.order_id WHERE o.total_amount <> COALESCE(l.line_total, 0)))
  UNION ALL
  SELECT 'every order has one matching payment',
         IF((SELECT COUNT(*) FROM sales_order o LEFT JOIN payment p ON p.order_id = o.order_id WHERE p.payment_id IS NULL OR p.amount <> o.total_amount OR ((p.status IN ('SUCCEEDED','PARTIALLY_REFUNDED','REFUNDED')) <> (p.paid_at IS NOT NULL))) = 0, 'PASS', 'FAIL'),
         CONCAT('invalid_orders=', (SELECT COUNT(*) FROM sales_order o LEFT JOIN payment p ON p.order_id = o.order_id WHERE p.payment_id IS NULL OR p.amount <> o.total_amount OR ((p.status IN ('SUCCEEDED','PARTIALLY_REFUNDED','REFUNDED')) <> (p.paid_at IS NOT NULL))))
  UNION ALL
  SELECT 'order and payment statuses agree',
         IF((SELECT COUNT(*) FROM sales_order o JOIN payment p ON p.order_id = o.order_id WHERE o.status <> CASE p.status WHEN 'PENDING' THEN 'PENDING_PAYMENT' WHEN 'FAILED' THEN 'PAYMENT_FAILED' WHEN 'SUCCEEDED' THEN 'PAID' WHEN 'PARTIALLY_REFUNDED' THEN 'PARTIALLY_REFUNDED' WHEN 'REFUNDED' THEN 'REFUNDED' END) = 0, 'PASS', 'FAIL'),
         CONCAT('status_mismatches=', (SELECT COUNT(*) FROM sales_order o JOIN payment p ON p.order_id = o.order_id WHERE o.status <> CASE p.status WHEN 'PENDING' THEN 'PENDING_PAYMENT' WHEN 'FAILED' THEN 'PAYMENT_FAILED' WHEN 'SUCCEEDED' THEN 'PAID' WHEN 'PARTIALLY_REFUNDED' THEN 'PARTIALLY_REFUNDED' WHEN 'REFUNDED' THEN 'REFUNDED' END))
  UNION ALL
  SELECT 'customer and shipping regions agree',
         IF((SELECT COUNT(*) FROM sales_order o JOIN customer c ON c.customer_id = o.customer_id WHERE o.ship_region_code <> c.region_code OR o.ship_region_name <> c.region_name) = 0, 'PASS', 'FAIL'),
         CONCAT('region_mismatches=', (SELECT COUNT(*) FROM sales_order o JOIN customer c ON c.customer_id = o.customer_id WHERE o.ship_region_code <> c.region_code OR o.ship_region_name <> c.region_name))
  UNION ALL
  SELECT 'refund quantity and amount stay within paid order lines',
         IF((SELECT COUNT(*) FROM refund r JOIN order_item i ON i.order_item_id = r.order_item_id JOIN payment p ON p.payment_id = r.payment_id WHERE r.quantity > i.quantity OR r.amount > i.line_total OR p.paid_at IS NULL) = 0, 'PASS', 'FAIL'),
         CONCAT('invalid_refunds=', (SELECT COUNT(*) FROM refund r JOIN order_item i ON i.order_item_id = r.order_item_id JOIN payment p ON p.payment_id = r.payment_id WHERE r.quantity > i.quantity OR r.amount > i.line_total OR p.paid_at IS NULL))
  UNION ALL
  SELECT 'refund dates stay within the observation cutoff',
         IF((SELECT COUNT(*) FROM refund r JOIN dataset_metadata d ON d.dataset_code = 'SYNTHETIC_ECOMMERCE_V1' WHERE DATE(r.refunded_at) > d.refund_observation_cutoff) = 0, 'PASS', 'FAIL'),
         CONCAT('refunds_after_cutoff=', (SELECT COUNT(*) FROM refund r JOIN dataset_metadata d ON d.dataset_code = 'SYNTHETIC_ECOMMERCE_V1' WHERE DATE(r.refunded_at) > d.refund_observation_cutoff))
  UNION ALL
  SELECT 'refund total stays within payment principal',
         IF((SELECT COUNT(*) FROM payment p JOIN refund_order_totals r ON r.order_id = p.order_id WHERE r.refunded_amount > p.amount) = 0, 'PASS', 'FAIL'),
         CONCAT('over_refunded_orders=', (SELECT COUNT(*) FROM payment p JOIN refund_order_totals r ON r.order_id = p.order_id WHERE r.refunded_amount > p.amount))
  UNION ALL
  SELECT 'west region October order volume declines',
         IF((SELECT COUNT(*) FROM sales_order WHERE ship_region_code = 'west' AND order_date >= '2026-10-01' AND order_date < '2026-11-01') < (SELECT COUNT(*) FROM sales_order WHERE ship_region_code = 'west' AND order_date >= '2026-09-01' AND order_date < '2026-10-01'), 'PASS', 'FAIL'),
         CONCAT('September=', (SELECT COUNT(*) FROM sales_order WHERE ship_region_code = 'west' AND order_date >= '2026-09-01' AND order_date < '2026-10-01'), ', October=', (SELECT COUNT(*) FROM sales_order WHERE ship_region_code = 'west' AND order_date >= '2026-10-01' AND order_date < '2026-11-01'))
  UNION ALL
  SELECT 'west has the largest absolute sales decline',
         IF((SELECT october_sales - september_sales FROM region_sales WHERE ship_region_code = 'west') = (SELECT MIN(october_sales - september_sales) FROM region_sales), 'PASS', 'FAIL'),
         CONCAT('west_delta=', (SELECT october_sales - september_sales FROM region_sales WHERE ship_region_code = 'west'))
  UNION ALL
  SELECT 'electronics has the highest refund rate and exceeds 10 percent',
         IF((SELECT refunded_units / sold_units FROM category_refund_rates WHERE category_code = 'electronics') > 0.10 AND (SELECT refunded_units / sold_units FROM category_refund_rates WHERE category_code = 'electronics') = (SELECT MAX(refunded_units / sold_units) FROM category_refund_rates), 'PASS', 'FAIL'),
         CONCAT('electronics_rate=', ROUND((SELECT refunded_units / sold_units FROM category_refund_rates WHERE category_code = 'electronics'), 4))
  UNION ALL
  SELECT 'all business rows are explicitly synthetic',
         IF((SELECT COUNT(*) FROM customer WHERE synthetic_data <> TRUE)
          + (SELECT COUNT(*) FROM product_category WHERE synthetic_data <> TRUE)
          + (SELECT COUNT(*) FROM product WHERE synthetic_data <> TRUE)
          + (SELECT COUNT(*) FROM sales_order WHERE synthetic_data <> TRUE)
          + (SELECT COUNT(*) FROM order_item WHERE synthetic_data <> TRUE)
          + (SELECT COUNT(*) FROM payment WHERE synthetic_data <> TRUE)
          + (SELECT COUNT(*) FROM refund WHERE synthetic_data <> TRUE) = 0, 'PASS', 'FAIL'),
         'synthetic_data flags checked on every business table'
)
SELECT check_name, result, observed FROM checks ORDER BY check_name;

WITH region_sales AS (
  SELECT o.ship_region_code,
         SUM(CASE WHEN p.paid_at >= '2026-09-01' AND p.paid_at < '2026-10-01' THEN p.amount ELSE 0 END) AS september_sales,
         SUM(CASE WHEN p.paid_at >= '2026-10-01' AND p.paid_at < '2026-11-01' THEN p.amount ELSE 0 END) AS october_sales
  FROM sales_order o JOIN payment p ON p.order_id = o.order_id
  WHERE p.status IN ('SUCCEEDED', 'PARTIALLY_REFUNDED', 'REFUNDED')
    AND p.paid_at >= '2026-09-01' AND p.paid_at < '2026-11-01'
  GROUP BY o.ship_region_code
)
SELECT r.ship_region_code,
       r.september_sales,
       r.october_sales,
       r.october_sales - r.september_sales AS sales_change
FROM region_sales r
ORDER BY sales_change;

WITH refund_item_totals AS (
  SELECT order_item_id, SUM(quantity) AS refunded_quantity
  FROM refund WHERE status = 'COMPLETED' GROUP BY order_item_id
),
category_refund_rates AS (
  SELECT c.category_code, SUM(i.quantity) AS sold_units, SUM(COALESCE(r.refunded_quantity, 0)) AS refunded_units
  FROM product_category c
  JOIN product p ON p.category_id = c.category_id
  JOIN order_item i ON i.product_id = p.product_id
  JOIN payment pay ON pay.order_id = i.order_id AND pay.status IN ('SUCCEEDED', 'PARTIALLY_REFUNDED', 'REFUNDED')
  LEFT JOIN refund_item_totals r ON r.order_item_id = i.order_item_id
  GROUP BY c.category_code
)
SELECT category_code,
       sold_units,
       refunded_units,
       ROUND(refunded_units / sold_units, 4) AS refund_rate
FROM category_refund_rates
ORDER BY refund_rate DESC;
