# DataPilot development rules

- Read `docs/implementation-plan.md` before starting or extending a development stage.
- Stay within the stage the user authorized. Report stage progress only after its recorded checks pass.
- Never commit API keys, database passwords, `.env` files, customer data, or fabricated query results.
- Mark generated business data synthetic. Use the documented metric definitions and evidence from executed queries.
- Never execute a model-produced query until the server-side SQL AST allowlist and read-only authorization have passed.
- Keep migrations and data seeding credentials separate from the read-only application account.
- For changes involving a framework or dependency API, use Context7 to check the current official docs first.
- Use the repository PowerShell commands and Maven Wrapper. Do not change global Git configuration.
- After each requested stage, run its documented tests and builds, fix failures, and update the implementation plan.

## Project overview

DataPilot is an AI data-analysis demo platform: a Vue 3 workbench (dashboard, manual SQL, Chinese natural-language AI analysis) backed by a Spring Boot service that turns questions into SQL, validates it through a fail-closed AST policy, and runs it read-only against a synthetic ecommerce MySQL dataset (100,000 seeded orders, fixed seed).

Stack: Java 21, Spring Boot 4.1.2, Maven Wrapper 3.9.16; Vue 3 + TypeScript + Vite + ECharts (Node >= 22.12); MySQL 8.4.13 via Docker Compose; OpenAI-compatible chat APIs (Qiniu default; optional FreeLLMAPI local and ModelScope providers, switchable in the workbench).

## Layout

- `backend/` — Spring Boot app under `com.datapilot`: `ai` (+ `ai/evaluation`), `analytics`, `common/web`, `query`, `seed`. REST prefix `/api/v1`; JSqlParser 5.4 drives the SQL AST policy.
- `frontend/` — `src/App.vue` is the main workbench; `src/components/DataVisualizations.vue`; `src/analysis/result-analysis.js` (+ `.d.ts`) is a plain-JS analysis module tested with `node --test`.
- `database/` — `init/` DDL, users, analytics views; `migrations/`; `validation/` business checks.
- `scripts/` — all PowerShell entry points (setup, seed, validate, verify, evaluate).
- `docs/` — `implementation-plan.md` is the authoritative stage-gate and verification record; see also `sql-query-safety.md`, `ai-text2sql.md`, `text2sql-evaluation.md`, `ai-visualization.md`.

## Commands (PowerShell, from repo root)

- Full verification after changes: `.\scripts\verify-project.ps1` (backend `mvnw clean verify` + frontend unit tests, typecheck, build).
- Backend only: `cd backend; .\mvnw.cmd -B -ntp clean verify`; focused tests: `.\mvnw.cmd -B -ntp test "-Dtest=ClassName"`.
- Frontend only: `cd frontend; npm run typecheck | npm run test:unit | npm run build` (build runs typecheck first).
- Database: `.\scripts\start-mysql.ps1`, then `seed-synthetic-data.ps1`, then `validate-dataset.ps1` (expects 12/12 PASS).
- Text2SQL eval: `.\scripts\evaluate-text2sql.ps1 -Mode mock` (deterministic, no API calls); live mode is rate-limited — pass `-MaxCalls`/`-IntervalSeconds`. Results go to git-ignored `.runtime/evaluations/`.

## Architecture boundaries

- AI endpoints (`/api/v1/ai/*`) only generate and validate SQL; execution always goes through the same read-only query pipeline as manual SQL — never execute model output directly.
- Query guardrails are non-negotiable: fail-closed AST allowlist (statement types, tables/columns, functions), `EXPLAIN` cost check, 5-second deadlines, concurrency limit 4, 200-row result cap regardless of SQL `LIMIT`.
- DECIMAL and BIGINT are returned as decimal strings in query APIs to avoid JS rounding — do not change this when touching serialization or the frontend.
- Database accounts stay separate: `datapilot_reader` (app; SELECT + metadata-only SHOW VIEW), `datapilot_seed` (seeding), root (init/migration).
- Model keys (`QINIU_API_KEY`, `DATAPILOT_AI_LOCAL_API_KEY`, `DATAPILOT_AI_MODELSCOPE_API_KEY`) live only in the backend process environment — never in `.env.example`, frontend code, or logs.

## Environment gotchas (Windows)

- Prefer the repository PowerShell scripts over raw `mvn`/`npm`: they import `.env` (via `import-local-env.ps1`) and apply the process-scoped `-Djdk.net.unixdomain.tmpdir` workaround that live Tomcat launches need on this machine.
- MySQL binds to `127.0.0.1:3307` (not 3306); Vite dev server runs on strict port 5173 and proxies `/api` and `/actuator` to the backend on `127.0.0.1:8080`.
- `.env` (random DB passwords) and `.runtime/` (eval output, JDK sockets) are git-ignored; never commit or inline their contents.
