# DataPilot

An interview-ready, safety-first AI data-analysis application built around verifiable database results. The project is being developed in reviewed, runnable stages.

> **Current stage: Stage 4 — Qiniu AI Text2SQL.** The dashboard and manual SQL workbench remain available. Natural-language requests use the backend model client; generated SQL always passes the Stage 3 AST policy and the same read-only executor.

## Technology

- Java 21, Spring Boot 4.1.2, Spring AI 2.0.2, Maven Wrapper 3.9.16
- Vue 3, TypeScript, Vite, ECharts
- MySQL 8.4.13 and Docker Compose

## Requirements

JDK 21, Node.js 22.12 or later, npm, Git, and Docker Desktop with a running Docker Engine and Compose plugin.

Check the local environment from PowerShell:

```powershell
.\scripts\check-environment.ps1
```

## Start the database and seed data

Run these commands from the project root in PowerShell. The first command creates an ignored `.env` with local random database credentials and starts only this project's MySQL service.

```powershell
.\scripts\start-mysql.ps1
.\scripts\seed-synthetic-data.ps1
.\scripts\validate-dataset.ps1
```

The default seed contains 100,000 orders. On the development machine the fixed-seed batch run completed in about 15 seconds; actual timing depends on the machine. To exercise a larger dataset, pass any count from 10 to 1,000,000, for example `\.\scripts\seed-synthetic-data.ps1 -OrderCount 250000`. Rerunning the seeder replaces rows only in the dedicated `datapilot` schema after checking both its database name and synthetic dataset marker. The named MySQL volume persists across restarts.

Database accounts are split by purpose: `datapilot_reader` has `SELECT` and metadata-only `SHOW VIEW` on `datapilot.*` and is used by the web application; `datapilot_seed` has only `SELECT`, `INSERT`, `UPDATE`, and `DELETE` on that schema and is used only by the seeder. The MySQL root password is used only inside the database container. Versioned schema files in `database/init/` run when the named volume is first initialized.

## Start the services

Open two PowerShell terminals from the project root:

```powershell
.\scripts\start-backend.ps1
```

```powershell
.\scripts\start-frontend.ps1
```

Open http://127.0.0.1:5173. The dashboard calls `GET /api/v1/analytics/overview` and displays the response read from MySQL. The SQL workbench provides validation, execution, sample analyses, and a bounded results table.

The AI workbench uses `QINIU_API_KEY` from the Windows user or process environment. The backend must be started after the variable is available. It defaults to Qiniu AI's OpenAI-compatible endpoint `https://api.qnaigc.com/v1` and model `qiniu/conatus-free`; it never sends the key to the browser. Keep the key outside Git and logs. To switch to another OpenAI-compatible provider, set `DATAPILOT_AI_BASE_URL`, `DATAPILOT_AI_MODEL`, and `DATAPILOT_AI_PROVIDER` in the backend process environment.

Apply the versioned aggregate views to an existing local database after updating to this stage:

```powershell
.\scripts\apply-query-views.ps1
```

Newly initialized databases create the views automatically. They are `SQL SECURITY INVOKER` views over the existing synthetic tables; no business rows are changed.

## SQL query API

- `POST /api/v1/query/validate` — server-side MySQL-dialect AST parsing and policy checks.
- `POST /api/v1/query/execute` — revalidates the SQL, then executes it as `datapilot_reader`; maximum 200 returned rows.
- `GET /api/v1/metrics` — business metric definitions, formulas, filters, dimensions, and fields.

Example request:

```powershell
$body = @{ sql = "SELECT paid_month, paid_order_count, paid_amount FROM v_monthly_paid_sales ORDER BY paid_month" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/v1/query/execute -ContentType 'application/json' -Body $body
```

The validation endpoint returns `allowed`, `errorCode`, and a safe message. Execution returns column names and database types, rows, `rowCount`, `executionTimeMs`, and `truncated`. It returns controlled error codes such as `SQL_PARSE_ERROR`, `UNAUTHORIZED_TABLE`, `UNAUTHORIZED_COLUMN`, `QUERY_PLAN_LIMIT`, `QUERY_TIMEOUT`, and `QUERY_BUSY`; it does not return SQL or database exception text.

See [docs/sql-query-safety.md](docs/sql-query-safety.md) for supported SQL, limits, metric definitions, and additional request examples.

## AI Text2SQL API

- `POST /api/v1/ai/generate` — generates SQL and returns its server-side validation; this endpoint does not execute the query.
- `POST /api/v1/ai/query` — generates, validates, and executes in one request through the existing bounded read-only query service.

Example one-click analysis:

```powershell
$body = @{ question = "列出 2026 年每月实付金额和已支付订单数" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/v1/ai/query -ContentType 'application/json' -Body $body
```

For a user-confirmed flow, call `/api/v1/ai/generate`, inspect `sql` and `validation.allowed`, then place the SQL in the existing SQL workbench and execute it. The `/api/v1/query/execute` endpoint revalidates the SQL independently. See [docs/ai-text2sql.md](docs/ai-text2sql.md) for provider settings, prompt versioning, repair rules, and the 20-question evaluation corpus.

## Build and test

Run after starting MySQL and seeding the default 100,000-order dataset. This command imports the ignored `.env`, then Maven clean `verify` runs the live-MySQL `*IT` checks for data and reader permissions, followed by the frontend type check and production build.

```powershell
\.\scripts\verify-project.ps1
```

The Maven Wrapper downloads and verifies Maven 3.9.16 on its first run. `npm ci` installs the committed `package-lock.json` exactly.

## Database and credentials

`scripts/setup-local-env.ps1` creates `.env` from the committed template and generates local database passwords. `.env` is ignored by Git. Never commit it or paste its values into logs. The AI key is read only by the backend from `QINIU_API_KEY`; no model key is stored in `.env`, source files, or frontend assets.

Key files:

- `database/init/001-schema.sql`: relationships, constraints, indexes, synthetic-data markers, and the fixed refund observation cutoff (`2026-11-06`).
- `database/init/003-analytics-views.sql`: safe, invoker-security aggregate views for paid sales and refund analysis.
- `database/migrations/002-refund-observation-cutoff.sql`: update for a local database created from the intermediate v1 schema during development.
- `database/init/002-users.sh`: database-scoped reader and seed accounts.
- `database/validation/001-business-checks.sql`: payment/refund consistency and expected business trends.
- `backend/src/main/java/com/datapilot/seed/`: fixed-seed batch generator.
- `backend/src/main/resources/prompts/text2sql-v1.txt`: versioned system prompt with schema and metric context injected at runtime.
- `backend/src/test/resources/text2sql/evaluation-v1.json`: 20 natural-language evaluation questions with reference SQL and canonical values.
- `docs/ai-text2sql.md`: Qiniu configuration, API usage, safety and repair behavior, and evaluation instructions.
- `docs/troubleshooting.md`: startup and database recovery guidance.
- `docs/implementation-plan.md`: original scope, accepted decisions, stage checks, and required model-driven Text2SQL closure.
