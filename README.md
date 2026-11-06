# DataPilot

An interview-ready, safety-first AI data-analysis application built around verifiable database results. The project is being developed in reviewed, runnable stages.

> **Current stage: Stage 2 — MySQL and synthetic ecommerce data.** Dashboard values come from the local MySQL database and are labeled as synthetic. Model-driven Text2SQL is not active yet.

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

The default seed contains 100,000 orders. To exercise a larger dataset, pass any count from 10 to 1,000,000, for example `\.\scripts\seed-synthetic-data.ps1 -OrderCount 250000`. Rerunning the seeder replaces rows only in the dedicated `datapilot` schema after checking both its database name and synthetic dataset marker. The named MySQL volume persists across restarts.

Database accounts are split by purpose: `datapilot_reader` has only `SELECT` on `datapilot.*` and is used by the web application; `datapilot_seed` has only `SELECT`, `INSERT`, `UPDATE`, and `DELETE` on that schema and is used only by the seeder. The MySQL root password is used only inside the database container. Versioned schema files in `database/init/` run when the named volume is first initialized.

## Start the services

Open two PowerShell terminals from the project root:

```powershell
.\scripts\start-backend.ps1
```

```powershell
.\scripts\start-frontend.ps1
```

Open http://127.0.0.1:5173. The dashboard calls `GET /api/v1/analytics/overview` and displays the response read from MySQL. If the database or seed is unavailable, it shows an error rather than invented results.

## Build and test

Run after starting MySQL and seeding the default 100,000-order dataset. This command imports the ignored `.env`, then Maven clean `verify` runs the live-MySQL `*IT` checks for data and reader permissions, followed by the frontend type check and production build.

```powershell
\.\scripts\verify-project.ps1
```

The Maven Wrapper downloads and verifies Maven 3.9.16 on its first run. `npm ci` installs the committed `package-lock.json` exactly.

## Database and credentials

`scripts/setup-local-env.ps1` creates `.env` from the committed template and generates local database passwords. `.env` is ignored by Git. Never commit it or paste its values into logs. The DeepSeek key is optional and unused in Stage 2; model calls remain disabled until the Text2SQL safety pipeline and evaluation are implemented.

Key files:

- `database/init/001-schema.sql`: relationships, constraints, indexes, synthetic-data markers, and the fixed refund observation cutoff (`2026-11-06`).
- `database/migrations/002-refund-observation-cutoff.sql`: update for a local database created from the intermediate v1 schema during development.
- `database/init/002-users.sh`: database-scoped reader and seed accounts.
- `database/validation/001-business-checks.sql`: payment/refund consistency and expected business trends.
- `backend/src/main/java/com/datapilot/seed/`: fixed-seed batch generator.
- `docs/troubleshooting.md`: startup and database recovery guidance.
- `docs/implementation-plan.md`: original scope, accepted decisions, stage checks, and required model-driven Text2SQL closure.
