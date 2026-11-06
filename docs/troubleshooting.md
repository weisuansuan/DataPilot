# Local database troubleshooting

All commands in this guide operate on the Compose project in this repository. They do not connect to any other MySQL installation.

## Docker or Compose is unavailable

Run `\.\scripts\check-environment.ps1`. It checks the Docker CLI, Engine, and Compose plugin. Start Docker Desktop, wait for the Engine, then retry `\.\scripts\start-mysql.ps1`.

## The MySQL container is unhealthy

Inspect this Compose project's logs and service state:

```powershell
docker compose ps
docker compose logs --tail 100 mysql
```

Check that port 3307 is not in use. Update `DATAPILOT_DB_PORT` in the ignored `.env` file if it conflicts, then rerun `\.\scripts\start-mysql.ps1`. The service binds only to `127.0.0.1`.

## MySQL initialization did not create users or tables

MySQL runs files from `database/init/` only when its data directory is empty. Check logs for the first initialization error. The project uses the persistent Compose volume `datapilot_datapilot_mysql_data` (Compose may prefix it with the project name). Do not remove it as a generic troubleshooting step: it contains the local dataset. If an initialization script needs a fix after the volume already exists, apply a reviewed migration to this database or recreate the development database only after confirming the exact project and volume.

For this project's intermediate v1 database, run `\.\scripts\apply-refund-cutoff-migration.ps1` once before reseeding. The script checks for the column first and targets only this repository's Compose database. Fresh databases already include the cutoff column in `001-schema.sql`.

## The API reports a database error

1. Confirm `docker compose ps` shows `mysql` healthy.
2. Run `\.\scripts\validate-dataset.ps1`; if no dataset exists, run `\.\scripts\seed-synthetic-data.ps1`.
3. Restart `\.\scripts\start-backend.ps1` so it imports the current `.env` values.
4. The application connects as `datapilot_reader`; it cannot create or alter tables. The seeder runs separately as `datapilot_seed`.

The API's safe error response does not include SQL text or credentials. The backend log includes a request ID to correlate the failure.

## Reproducible data regeneration

Rerunning the seeder deletes and regenerates rows in the `datapilot` schema only after it confirms the current database and `SYNTHETIC_ECOMMERCE_V1` marker. The seed generator does not drop tables or modify schema. It accepts 10 through 1,000,000 orders. A failed run can be rerun with the same count and fixed seed.

The committed validation SQL can also be run manually:

```powershell
\.\scripts\validate-dataset.ps1
```

It checks the returned `PASS` rows, payment/refund consistency, West-region September-to-October decline, and the elevated electronics refund rate.
