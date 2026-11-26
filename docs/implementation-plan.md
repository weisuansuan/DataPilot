# DataPilot implementation plan

This record preserves the original project requirements and approved first-round decisions. Stage completion means the evidence below has actually been produced; a planned check or mock provider response never counts as a passing real integration.

## Project goal and full scope

Build an interview-ready AI data-analysis platform using Java 21, Spring Boot, MySQL 8, Maven, Vue 3, and ECharts. A user asks a business question in natural language. The application exposes its business schema and metric definitions to an AI model, turns its structured query plan into SQL, validates the SQL independently, executes only approved read-only queries, and presents the real result, its source, and a result-backed chart and explanation.

Provide a repeatable synthetic ecommerce dataset with customers, products, orders, order items, payments, and refunds. Generate 100,000 internally consistent orders by default; allow scale-up to one million. Label every seed, database view, page, fixture, and exported result as synthetic. Include predesigned regional sales declines and an increase in one category's refund rate, together with standard SQL that verifies the expected rankings and trends.

Do not execute SQL because it starts with `SELECT`. Require full SQL AST parsing, fail-closed restrictions on statement types and syntax, table and column allowlists, an independently scoped read-only account, server and client execution deadlines, bounded results, no multiple statements, blocked high-risk operations, and useful error and invocation records. Model-generated SQL and analysis must never bypass those controls.

## Agreed decisions

- Build one Spring application, one Vue application, and one Compose-managed MySQL instance. No microservices, queue, RAG, MCP, user accounts, complex permission system, or multi-agent workflow in the first round.
- Use Spring Boot 4.1.2 and the user-specified Qiniu AI OpenAI-compatible endpoint. Spring AI 2.0.2 was evaluated; Stage 4 uses a small JDK 21 `HttpClient` abstraction for explicit SSE/body deadline control. Provider, base URL, model, stream mode, and limits are configuration-driven; `QINIU_API_KEY` stays backend-only.
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
| 4. Qiniu AI Text2SQL | Configurable OpenAI-compatible client; versioned prompt; natural-language generation and one-click query APIs; bounded syntax/schema repair; Vue AI workbench; 20-question reference corpus | Mock protocol/orchestration tests; all reference SQL runs against MySQL; Qiniu API smoke and end-to-end call when the key is available; browser validation; every model SQL goes through Stage 3 policy and executor | **Complete** |
| 5. AI reliability and data visualization | Classify/cool down provider limits; bounded offline/live semantic evaluation; exact result comparison; deterministic chart recommendation and factual insights; reduce initial JS payload | Full offline MySQL corpus report; bounded live calls when available; rate-limit/timeout tests; chart and insight tests; browser graph/table interaction; Maven, dataset and frontend regression gates | **Complete** |

### Stage 1 verification (2026-11-06)

- `backend/mvnw.cmd -B -ntp clean verify`: **BUILD SUCCESS**, 3 tests passed, 0 failures/errors/skips, on Java 21.0.11 with Maven Wrapper 3.9.16.
- Started the actual Spring Boot 4.1.2 app with no database or model key. `/actuator/health/liveness` and `/actuator/health/readiness` both returned `UP`; an unknown route returned HTTP 404 with `NOT_FOUND` and a request ID.
- Frontend `npm ci --no-audit --no-fund`, `npm run typecheck`, and `npm run build` passed. The running Vite page returned HTTP 200, and its proxied backend liveness check returned HTTP 200 / `UP`.
- Environment check found Docker CLI 29.9.2, Engine 29.9.2, and Compose 5.6.1 available. Docker is installed and running; database setup remains Stage 2.
- Windows Java 21 in this execution environment needed a process-scoped Unix-domain socket temp directory for a live Tomcat launch. `scripts/start-backend.ps1` applies this workaround without changing machine-wide configuration.
- `.env.example` has no credential and model calls remained disabled during Stage 1. Stage 4 now uses the separately configured Qiniu AI environment key.

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

### Stage 4 verification (2026-11-07)

- Added a Java 21 `HttpClient` implementation of the OpenAI-compatible Qiniu Chat Completions API with configurable model/base URL/stream mode, bounded request and response sizes, request and body deadlines, SSE event assembly through `[DONE]`, optional usage parsing, and sanitized provider error codes. The key is read only by the backend from `QINIU_API_KEY`.
- Added versioned Chinese Text2SQL prompt, allowlist and metric context injection, structured output parsing, clarification/unsupported outcomes, and at most two syntax/schema repair attempts. Each candidate SQL is re-parsed by the existing Stage 3 policy and executed only via the existing plan review, `datapilot_reader`, timeout, concurrency, and result cap.
- Added `/api/v1/ai/generate` and `/api/v1/ai/query`, configurable AI settings, and the Vue AI analysis panel with question presets, generation/confirmation/execution, response details, and safe provider errors. The existing dashboard and manual SQL workbench remain available.
- `scripts/verify-project.ps1` completed Maven `clean verify`: **25 unit tests and 10 real-MySQL integration tests passed**, with 0 failures, errors, or skips. Integration coverage includes all 20 reference queries against the seeded MySQL database and a mock-generated SQL workflow through the actual policy and query service. `npm run typecheck` and `npm run build` passed.
- `scripts/validate-dataset.ps1` passed **12/12** checks; generated business rows were not changed. The production build emits a 587.50 kB minified JavaScript chunk warning (201.83 kB gzip).
- A live Qiniu JSON-mode `/api/v1/ai/generate` request succeeded using `qiniu/conatus-free`; it returned an allowed `COUNT(DISTINCT order_id)` query for the all-orders question. The live streaming `/api/v1/ai/query` and a later browser generation attempt received the provider's HTTP 429 rate limit, surfaced as `AI_RATE_LIMITED`; no SQL was executed from those attempts. SSE framing, `[DONE]`, usage, timeout, malformed response, and rate-limit handling passed deterministic mock tests. Live streaming could not be confirmed while the provider was rate-limiting.
- Browser verification loaded the fourth-stage AI workbench and dashboard. A preset business question showed the controlled rate-limit message; the existing SQL workbench executed its order summary query and rendered five result rows with typed columns. The independent MySQL workflow integration test confirms generated SQL results can pass through the same policy and executor.
- `docs/ai-text2sql.md` documents configuration, API examples, repair boundaries, provider behavior, and the 20-question reference corpus. Live model semantic accuracy remains Stage 5 work and is not claimed by this stage.

## Stage 5 verification (2026-11-07)

- Provider reliability: HTTP 429 handling distinguishes request-rate, quota/billing, model-capacity, provider-global, and unknown categories; parses bounded `Retry-After` and remaining-count headers; retries only recognized request-rate errors with configured finite backoff/jitter; applies process-local cooldown; coalesces concurrent duplicate AI questions; and returns sanitized errors. Mock tests cover these paths, cooldown and retry bounds.
- Evaluation: `scripts/evaluate-text2sql.ps1 -Mode mock` completed all **20/20** questions against the seeded MySQL database. Generation, AST approval, execution, result and metric accuracy were **20/20**; safety violations **0**; provider calls **0**. This is workflow/fixture verification, not live-model accuracy.
- Limited Qiniu checks used **4** active calls in total and stayed within the 5-call cap. One SSE evaluation sample Q01 generated safe SQL, executed and exactly matched its reference result (4,389 ms, 2,142 tokens). Two requests returned HTTP 429 `free_campaign_global_limit` without recognized retry/rate headers. One browser analysis response was not parseable; it returned `AI_INVALID_RESPONSE` and no SQL executed. No broad live accuracy or P95 claim is made.
- Precision and results: API DECIMAL/BIGINT output is encoded as decimal strings with MySQL type metadata. Comparator tests verify exact decimal equality and order-independent rows. A live API check returned 9 metrics, allowed an approved aggregate query, rejected `mysql.user`, and returned 10 monthly rows; DECIMAL values remained strings. A real region/month query returned the expected eight 2026-09/10 rows, and West's change was -1,254,631.00.
- Visualization: deterministic recommendations support KPI, category bars, date lines, pie composition and temporal-plus-category grouped bars/lines. Unsafe numeric precision, unsupported dimensions and empty results fall back to tables. Chinese conclusions derive only from result rows and state their comparison basis. Browser verification rendered the actual MySQL regional data in an ECharts canvas, switched to eight raw rows, and switched back with the chart re-created correctly. The factual result identified West as the greatest decline.
- Build performance: ECharts and result visualization are lazy-loaded and ECharts modules are split. `scripts/verify-project.ps1` completed Maven `clean verify` with **33 unit tests and 10 real-MySQL integration tests**, all passing, plus **8 frontend unit tests**, TypeScript type check, and production build. The initial JavaScript bundle is 82.48 kB; the largest ECharts chart bundle is 370.11 kB, with no chunk-size warning.
- `scripts/validate-dataset.ps1` passed **12/12** business checks; synthetic rows were not changed. API smoke checks verified metric catalog, AST allow/deny behavior and exact decimal serialization. README, AI provider, evaluation and visualization documentation are updated.

Online semantic accuracy beyond the single successful live sample remains unknown because Qiniu returned a provider-global 429 on limited attempts and one subsequent browser response was invalid. Continue live evaluation only within explicit call limits and once provider availability permits; do not treat the offline 20/20 result as online-model accuracy.

## Follow-up changes and verification (2026-11-18)

- Added a model registry and workbench selector for configured Qiniu, FreeLLMAPI local models discovered from `/models`, and ModelScope Kimi; provider credentials remain backend-only. Added Chinese user/setup documentation and updated provider/API guidance.
- Backend unit tests: **40 passed** with the repository's process-scoped `-Djdk.net.unixdomain.tmpdir` workaround. Frontend unit tests: **8 passed**; TypeScript type check and production build passed (entry JavaScript 84.62 kB; largest chart chunk 370.11 kB).
- Full `scripts/verify-project.ps1` was attempted. Its 10 MySQL integration tests could not pass because Docker Desktop's Linux engine pipe was absent and `scripts/start-mysql.ps1` could not start the database. The Docker Desktop executable was not present at the standard install paths. No schema or synthetic business rows were changed.
- Online Text2SQL evaluation was not run in this follow-up because the seeded MySQL service was unavailable; the historical limited live result above remains the only recorded online evidence. Resume with Docker Desktop running, validate the dataset, then use `scripts/evaluate-text2sql.ps1 -Mode live -MaxCalls 5` to preserve the call cap.

## Open-source preparation and verification (2026-11-26)

- Added the MIT license, contributor instructions, repository clone steps, and local-demo deployment scope. The existing personal interview guide stays local and is ignored; no application behavior or dependency version was changed.
- Checked all 7 existing commits (155 historical file blobs) for common credential patterns and exact matches to available local secret values; no matches were found. `.env`, `.runtime/`, database files and build outputs remain ignored. This check is a release-preparation measure, not a full security audit.
- The first `scripts/verify-project.ps1` attempt failed its MySQL integration checks while Docker was unavailable. Started the installed Docker Desktop and ran `scripts/start-mysql.ps1`; the existing database became healthy without reseeding.
- `scripts/validate-dataset.ps1`: **12/12 PASS** against the existing 100,000-order synthetic dataset.
- Reran `scripts/verify-project.ps1`: Maven `clean verify` **BUILD SUCCESS**, **40 unit tests and 10 real-MySQL integration tests passed**, with no failures, errors or skips. Frontend **8 unit tests**, TypeScript type check and production build all passed.
- `scripts/evaluate-text2sql.ps1 -Mode mock`: **20/20** generation, AST approval, execution and exact result comparison; **0** safety violations and **0** provider API calls. Online-model accuracy was not reevaluated.

## Operational boundaries

Run project commands from Windows PowerShell using the root scripts and `backend/mvnw.cmd`. Keep services on loopback for local development. A failure in an earlier gate pauses dependent stage work and is recorded with the command, actual output and next action. Do not silently skip tests or report unrun checks as passing. Offline fixture accuracy does not imply live-model semantic accuracy.
