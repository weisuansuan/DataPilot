# SQL query safety and metrics

## Request path

`POST /api/v1/query/validate` and `POST /api/v1/query/execute` both run the same server-side policy. Execute always validates again; the browser cannot authorize a query. JSqlParser 5.4 parses the MySQL dialect to a statement AST. The policy requires exactly one `SELECT`, walks tables, columns, and function calls through the parser visitor, checks object and field allowlists, and rejects parse errors. It rejects MySQL executable comments and optimizer hints before parsing.

Execution uses the existing Hikari `JdbcTemplate` data source, configured with `datapilot_reader`, `read-only: true`, and the existing maximum pool size. That database account has `SELECT` and metadata-only `SHOW VIEW` on `datapilot.*`; it has no data or schema write privileges. A semaphore caps simultaneous ad hoc queries at four by default. Before execution, the service runs `EXPLAIN FORMAT=JSON` on the validated statement and rejects a missing or over-limit optimizer cost estimate. The default `DATAPILOT_QUERY_MAX_PLAN_COST` ceiling is 10,000,000. Each query gets a JDBC timeout and a MySQL session `max_execution_time` deadline (5 seconds by default); the session setting is reset before the connection is returned. If reset fails, the connection is aborted. JDBC `setMaxRows(201)` caps retrieval independently of any user `LIMIT`; the API returns the first 200 rows and reports `truncated=true` when a 201st row exists.

Timeout, plan rejections, and database exceptions map to stable error codes and safe messages. SQL text, database error messages, credentials, and stack traces are not sent to the client. The request ID filter remains active for API tracing. Configure `DATAPILOT_QUERY_TIMEOUT_SECONDS` (1–30 seconds), `DATAPILOT_QUERY_MAX_CONCURRENCY` (1–8), and `DATAPILOT_QUERY_MAX_PLAN_COST` in the local environment.

## SQL support and limits

Allowed data objects are the seven Stage 2 tables (`customer`, `product_category`, `product`, `sales_order`, `order_item`, `payment`, `refund`) and the five analytics views (`v_paid_order`, `v_refund_by_order`, `v_refund_by_item`, `v_category_paid_sales`, `v_monthly_paid_sales`). The database/schema must not be qualified. Every column is checked against the selected table or view metadata; aliases work for table qualifiers and select expressions. `*` is accepted only when its source table is allowlisted.

The AST permits ordinary `SELECT`, joins, `WHERE`, `GROUP BY`, `HAVING`, `ORDER BY`, aggregate functions, scalar subqueries, and non-recursive CTEs. Approved functions are `COUNT`, `SUM`, `AVG`, `MIN`, `MAX`, `COALESCE`, `IFNULL`, `NULLIF`, `ROUND`, `ABS`, `YEAR`, `MONTH`, `QUARTER`, `DATE`, `DATE_FORMAT`, `YEARWEEK`, `CONCAT`, `LOWER`, `UPPER`, `SUBSTRING`, `LENGTH`, `CAST`, `GREATEST`, `LEAST`, `TIMESTAMPDIFF`, and `DATEDIFF` (case-insensitive). Customer email and payment/refund reference fields are excluded from the field allowlist.

The following are rejected: multiple statements; non-SELECT statements; database-qualified or non-allowlisted objects; unknown columns and functions; user variables and assignments; `SELECT INTO`; locking, wait, and skip-locked clauses; executable comments; optimizer hints; file, sleep, benchmark, lock, and other unapproved functions. Recursive CTEs, `UNION`, window functions, stored functions, table functions, and vendor-specific syntax have not been enabled in this stage. Parser errors and structures outside the supported visitor surface fail closed. SQL text is limited to 16,384 characters.

The policy is an application control alongside MySQL privileges, optimizer plan review, JDBC query deadlines, a bounded result set, and bounded concurrency. The optimizer estimate is heuristic; the server deadline remains in force even for a plan below the cost ceiling.

## Metric catalogue

All monetary arithmetic remains MySQL `DECIMAL` / Java `BigDecimal`; no float arithmetic is used in query execution.

| ID | Definition and cohort | Fanout guidance |
|---|---|---|
| `order_count` | Count distinct `sales_order.order_id`; all order states unless filtered | Count distinct order IDs across joins |
| `gross_paid_amount` | Sum successful payment principal by `payment.paid_at`; statuses `SUCCEEDED`, `PARTIALLY_REFUNDED`, `REFUNDED` | Payment is unique per order; do not join raw refund rows |
| `paid_amount` | Same paid principal as the existing dashboard; refunds are shown separately | `v_paid_order` provides one payment row per order |
| `refund_amount` | Sum completed refunds by `refund.refunded_at`; status `COMPLETED` | Aggregate by order or item before joining to other detail rows |
| `net_paid_amount` | Period paid principal minus period completed refund amount | Aggregate payment and refund separately, then subtract |
| `refund_rate` | Refunded item quantity divided by paid-order sold item quantity, cutoff 2026-11-06 | Use `v_refund_by_item`; never join raw refund rows directly to payment and order-item detail |
| `regional_sales` | Paid amount and distinct paid orders by shipping region and paid period | Use `v_paid_order` |
| `category_sales` | Paid order-item `line_total` by product category | One payment per order; aggregate refunds separately |
| `monthly_sales_trend` | Paid principal grouped by `payment.paid_at` month | `v_monthly_paid_sales` is pre-aggregated |

Views in `database/init/003-analytics-views.sql` are `SQL SECURITY INVOKER`. They preserve the Stage 2 payment and refund status definitions and do not change seeded rows. `scripts/apply-query-views.ps1` applies or refreshes them in an already initialized local database.

## API examples

Validate:

```powershell
$body = @{ sql = "SELECT paid_month, paid_order_count, paid_amount FROM v_monthly_paid_sales ORDER BY paid_month" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/v1/query/validate -ContentType 'application/json' -Body $body
```

Execute a regional aggregate:

```powershell
$body = @{ sql = "SELECT ship_region_name, SUM(paid_amount) AS paid_amount FROM v_paid_order GROUP BY ship_region_name ORDER BY paid_amount DESC" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/v1/query/execute -ContentType 'application/json' -Body $body
```

List metrics:

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/v1/metrics
```

No AI provider or model key is used by these endpoints.
