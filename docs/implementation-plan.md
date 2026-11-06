# DataPilot implementation plan

This record preserves the original project requirements and approved first-round decisions. Stage completion means the evidence below has actually been produced; a planned check or mock provider response never counts as a passing real integration.

## Project goal and full scope

Build an interview-ready AI data-analysis platform using Java 21, Spring Boot, MySQL 8, Maven, Vue 3, and ECharts. A user asks a business question in natural language. The application exposes its business schema and metric definitions to an AI model, turns its structured query plan into SQL, validates the SQL independently, executes only approved read-only queries, and presents the real result, its source, and a result-backed chart and explanation.

Provide a repeatable synthetic ecommerce dataset with customers, products, orders, order items, payments, and refunds. Generate 100,000 internally consistent orders by default; allow scale-up to one million. Label every seed, database view, page, fixture, and exported result as synthetic. Include predesigned regional sales declines and an increase in one category's refund rate, together with standard SQL that verifies the expected rankings and trends.

Do not execute SQL because it starts with `SELECT`. Require full SQL AST parsing, fail-closed restrictions on statement types and syntax, table and column allowlists, an independently scoped read-only account, server and client execution deadlines, bounded results, no multiple statements, blocked high-risk operations, and useful error and invocation records. Model-generated SQL and analysis must never bypass those controls.

## Agreed decisions

- Build one Spring application, one Vue application, and one Compose-managed MySQL instance. No microservices, queue, RAG, MCP, user accounts, complex permission system, or multi-agent workflow in the first round.
- Use Spring AI 2.0.2 with Spring Boot 4.1.2; use its OpenAI-compatible client for the user's preferred DeepSeek API, with provider/base URL/model configurable. Keep model calls disabled until Stage 5 and until the user supplies an application API credential. DeepSeek's current OpenAI-format endpoint and model are recorded in `.env.example`; recheck the official model catalog when Stage 5 begins.
- Keep credentials in local environment variables or ignored `.env` files. Never commit them.
- `sales_amount` means successfully paid principal recognized by `paid_at`. Report completed refunds as a separate metric. Rank requested monthly declines by absolute currency amount and display percentage change alongside it. Display the refund observation cutoff and define refund-rate cohort explicitly.
- For Stage 1, the webpage calls the real backend liveness endpoint. Until the database and safety stages exist, show no synthetic or fabricated analytics as results.
- Seed data uses a fixed random seed and a fixed reporting window. Repeated execution with the same settings must not duplicate orders.

## Stage gates

| Stage | Work | Acceptance evidence | Status |
|---|---|---|---|
| 1. Engineering foundation | Git, backend/frontend layout, Maven Wrapper 3.9.16, pinned npm dependencies and lockfile, configuration template, safe error format, health checks, PowerShell scripts, tests, README | Backend tests and Maven `verify`; Vue type check and production build; real backend health response if launchable; report Docker and Compose status | **Complete** |
| 2. MySQL and synthetic dataset | Versioned DDL, keys, checks and indexes; separate migration, seed and read-only accounts; Compose; deterministic Java batch seeder; standard business and anomaly-validation SQL | Start MySQL with Compose; seed 100,000 orders; repeat seed; assert counts, foreign keys, paid amounts, refund bounds, customer/category consistency, exact designed trend ranking, and no duplicate data | **Complete** |
| 3. SQL safety and query execution | Whitelisted metric catalogue and analytics views; bounded SQL parser and AST authorizer; JSON `EXPLAIN`; read-only executor; safe structured error and request logs; manual SQL workbench | Unit attacks against parser and AST nodes; live MySQL permission tests; reject multi-statement, writes, unauthorized tables/fields/functions, files, locking, unbounded results, excessive plans, and timeouts; browser and API query checks | **Complete** |
| 4. Real-database web analysis | Vue question and preset-analysis workbench; result table; ECharts configuration from returned columns and values; visible SQL, metric, source, synthetic-data notice, and supported fact-only explanation | Browser checks for approved queries against seeded MySQL; validate actual amounts against independent SQL; verify empty, truncated, malformed, rejected, failed and loading states | Not started |
| 5. Model-driven Text2SQL closure and evaluation | Configure the user's DeepSeek OpenAI-compatible API from environment variables; structured query-plan generation; independently enforce SQL safety and execution; bounded, sanitized execution-error feedback; measurable offline and live evaluation | Golden questions measure semantic equivalence of returned values, not textual SQL similarity; test ambiguity/clarification, attack rejection, schema drift and model/API errors; report per-question expected/actual values, accuracy, safety violations, latency and tokens/cost when returned | Not started |

### Stage 1 verification (2026-11-06)

- `backend/mvnw.cmd -B -ntp clean verify`: **BUILD SUCCESS**, 3 tests passed, 0 failures/errors/skips, on Java 21.0.11 with Maven Wrapper 3.9.16.
- Started the actual Spring Boot 4.1.2 app with no database or model key. `/actuator/health/liveness` and `/actuator/health/readiness` both returned `UP`; an unknown route returned HTTP 404 with `NOT_FOUND` and a request ID.
- Frontend `npm ci --no-audit --no-fund`, `npm run typecheck`, and `npm run build` passed. The running Vite page returned HTTP 200, and its proxied backend liveness check returned HTTP 200 / `UP`.
- Environment check found Docker CLI 29.9.2, Engine 29.9.2, and Compose 5.6.1 available. Docker is installed and running; database setup remains Stage 2.
- Windows Java 21 in this execution environment needed a process-scoped Unix-domain socket temp directory for a live Tomcat launch. `scripts/start-backend.ps1` applies this workaround without changing machine-wide configuration.
- `.env.example` has no credential and model calls remain disabled. The user has a DeepSeek key; no key is needed or used in Stage 1. Reconfirm the current provider model name when implementing Stage 5.

### Stage 2 verification (2026-11-06)

- Started the pinned MySQL 8.4.13 official image with the repository's Compose project; its named volume persists data and its health check verifies both limited accounts against the initialized schema. The host port binds only to `127.0.0.1:3307`.
- The default fixed-seed run generated 100,000 orders, 10,000 customers, 100 products, 5 categories, order lines, one payment attempt per order, and completed refunds. A second complete run with the same seed produced identical `CHECKSUM TABLE ... EXTENDED` checksums for all seven business tables.
- Enabled Connector/J `rewriteBatchedStatements` for the seeder's prepared-statement batches. The repeated 100,000-order run completed in 15.49 seconds on this machine, and all seven business-table checksums remained identical.
- `scripts/validate-dataset.ps1` returned **12/12 PASS** checks covering synthetic flags, metadata count, order/line totals, payment/order state, paid timestamps, customer regions, refund bounds and cutoff, the West-region volume/sales decline, and the highest category refund rate. West sales declined by 1,254,631.00 from September to October; electronics refund unit rate was 22.21%.
- `scripts/verify-project.ps1` ran Maven `verify` with 6 unit tests and 2 live-MySQL integration tests (all passed, 0 skipped), plus Vue type check and production build. Integration checks independently compare sales/refund aggregates and confirm the application account cannot update data or create tables.
- Started the real backend and Vite page. Chrome headless loaded the database-backed dashboard, rendered both ECharts canvases, displayed the synthetic label and refund observation cutoff, and recorded no browser runtime errors.
- A Windows Docker Desktop credential helper was installed outside the inherited shell PATH. `scripts/start-mysql.ps1` locates its per-user directory for the Compose invocation; no system PATH changes were made.

### Stage 3 verification (2026-11-06)

- Added JSqlParser 5.4 MySQL-dialect AST parsing with a fail-closed SELECT policy, recursive visitor checks, table/view and field allowlists, an explicit safe-function list, and controlled rejection of multiple statements, writes, schema-qualified objects, variables, file operations, locks, optimizer/executable comments, UNION, window functions, recursive CTEs, and unrecognized syntax. Safe joins, filters, aggregates, grouping, ordering, scalar subqueries, and non-recursive CTEs are covered.
- Customer email and payment/refund reference fields are excluded from the field allowlist; dedicated attacks confirm these fields are rejected.
- Added five `SQL SECURITY INVOKER` analytics views without modifying business rows. `datapilot_reader` retains SELECT and receives metadata-only `SHOW VIEW`; it has no data/schema write grants. Query execution adds JSON EXPLAIN cost review, default five-second JDBC and MySQL session deadlines, concurrency limit four, max 200 returned rows independent of SQL LIMIT, and controlled errors. Credentials and exception details are not returned.
- Added nine API metric definitions, validate/execute endpoints, and a Vue manual SQL workbench with examples, validation, result columns/types, duration, row count, and errors. AI provider calls remain disabled.
- `scripts/verify-project.ps1`: Maven `clean verify` **BUILD SUCCESS**; 9 unit tests and 7 real-MySQL integration tests passed, 0 failures/errors/skips. Coverage includes ordinary selects, joins, aggregates, CTE/subquery, write and multi-statement attacks, non-allowlisted relations/columns/functions, comments/hints, file/lock operations, timeout and connection reuse, plan ceiling, row cap, concurrency, exact DECIMAL agreement, and reader identity.
- `npm run typecheck` and `npm run build` passed. Build reports a 583.30 kB minified JavaScript chunk warning; it does not fail the build.
- `scripts/validate-dataset.ps1` returned **12/12 PASS**. Live APIs returned liveness UP, nine metrics, allowed validation for the monthly view query, and ten monthly rows. Their DECIMAL total was 73,414,488, exactly matching `GET /api/v1/analytics/overview`; unauthorized `mysql.user` was rejected as `UNAUTHORIZED_TABLE`; `LIMIT 500` still returned only 200 rows with `truncated=true`.
- Browser verification showed the connected dashboard and SQL workbench, including a successful regional/monthly JOIN query (40 result rows with VARCHAR/VARCHAR/DECIMAL columns) and a sanitized rejection for an unauthorized system table. The API acceptance used an isolated local backend on port 18080 because the pre-existing Stage 2 backend on 8080 was left running.
- Initial browser acceptance exposed that the live process held pooled connections from before the metadata grant. Restarting only the isolated Stage 3 process applied the grant; the view query then passed. The 8080 Stage 2 process was not stopped or modified.

## Required model-driven closure

The following is required before calling the full project complete; it is intentionally not Stage 1 functionality.

1. Send the user question, allowed schema, approved field definitions, and metric/time-cohort rules to the configured DeepSeek model (or another configured OpenAI-compatible provider). Request a typed query plan with explicit SQL, metric identifier, requested time bounds, dimensions, display columns, and any clarification request. Validate and bound the response before using any field from it.
2. Send the SQL to the same server-side, dialect-aware AST policy that protects preset queries. Validate every relation, qualified column, projection, predicate, join, function, literal, grouping, sort, limit, and nesting node using the minimum allowlisted SQL surface. Unsupported or ambiguous plans are rejected or clarified; never degrade into raw string execution.
3. Independently perform cost review and execute using the MySQL read-only account with deadlines and row/byte limits. Return only controlled error categories and safe schema corrections to the model for a maximum of two repair generations. The model never receives a database password, unrestricted schema, unbounded results, SQL privilege, or authority to disable a policy.
4. Summaries and chart specs must reference validated result columns and values. Calculate currency deltas, rates and rankings from the database result; do not let an ungrounded second model invent a cause. Display the exact executed SQL, metric definition, source views, reporting window, refund cutoff, synthetic-data label, duration and truncation state.
5. Commit versioned golden question, expected query/result, metric interpretation, and security fixtures. Include ordinary requests, month boundaries/time zones, nulls and zero denominators, refunds across periods, join fanout, ambiguous metrics, malformed/truncated model output, API timeouts and failures, prompt-injected SQL, multi-statement SQL and every prohibited AST class. Compare numeric results using exact decimal semantics and documented tolerances, not SQL text alone. Require zero safety-policy violations and a separately reported semantic-accuracy score for supported questions. Run deterministic mock-model tests in normal CI; make live provider evaluation an explicit, credentialed check, not an unverified claim.

## Operational boundaries

Run project commands from Windows PowerShell using the root scripts and `backend/mvnw.cmd`. Keep services on loopback for local development. A failure in an earlier gate pauses dependent stage work and is recorded with the command, actual output and next action. Do not silently skip tests, report unrun checks as passing, or mark the final Text2SQL capability complete before Stage 5 passes.
