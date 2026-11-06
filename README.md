# DataPilot

An interview-ready, safety-first AI data-analysis application built around verifiable database results. The project is being developed in reviewed, runnable stages.

> **Current stage: Stage 1 — engineering foundation.** No business database, synthetic business records, or live model connection has been added yet. The starter screen displays the actual Spring Boot health endpoint; it does not contain sample analytics presented as query results.

## Technology

- Java 21, Spring Boot 4.1.2, Spring AI 2.0.2, Maven Wrapper 3.9.16
- Vue 3, TypeScript, Vite, ECharts
- MySQL 8.4 and Docker Compose for a later development stage

## Requirements

JDK 21, Node.js 22.12 or later, npm, and Git. Docker Desktop with a running Docker Engine and Docker Compose plugin is needed for the database stage. Real model calls are deliberately disabled in Stage 1.

Check what is available from PowerShell:

```powershell
.\scripts\check-environment.ps1
```

## Start the services

Open two PowerShell terminals from the project root:

```powershell
.\scripts\start-backend.ps1
```

```powershell
.\scripts\start-frontend.ps1
```

Open http://127.0.0.1:5173. The starter screen checks the backend at http://127.0.0.1:8080/actuator/health/liveness. Stage 2 adds MySQL; its actual data flows become available only after the database implementation is verified.

## Build and test

```powershell
Push-Location backend
try { .\mvnw.cmd verify } finally { Pop-Location }
```

```powershell
Push-Location frontend
try {
    npm ci
    npm run typecheck
    npm run build
} finally { Pop-Location }
```

The Maven Wrapper downloads and verifies Maven 3.9.16 on its first run. `npm ci` installs the committed `package-lock.json` exactly.

## Configuration and credentials

Copy `.env.example` to `.env` as a local configuration starting point. `.env` is ignored by Git. Stage 1 needs no model key and does not make model calls. Keep all credentials out of source code, console transcripts, and commits. DataPilot's model integration requires its own supported model API credential; a coding-assistant subscription is not an application API credential. Set `DATAPILOT_LLM_ENABLED=true` only in a later stage after the model adapter and safe Text2SQL pipeline are implemented and tested.

## Docker Desktop

The database stage will use Docker Compose with a loopback-only published MySQL port. If the Docker CLI is missing in a new PowerShell session, restart that session to load the updated user PATH; the environment check also looks for Docker's per-user installation directory. If Docker Desktop is not installed, use Docker's [official Windows installation guide](https://docs.docker.com/desktop/setup/install/windows-install/). This project does not install system software or modify Windows virtualization settings.

## Project documentation

See [docs/implementation-plan.md](docs/implementation-plan.md) for the original scope, accepted decisions, stage checks, progress, and the required real model-driven Text2SQL and evaluation closure.
