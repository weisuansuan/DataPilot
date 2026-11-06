#!/bin/bash
set -eu

# Passwords are generated as hexadecimal by setup-local-env.ps1 so they are safe
# to interpolate into this init-only SQL heredoc. The values stay in .env.
MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" mysql --protocol=socket --user=root --database=datapilot <<-EOSQL
CREATE USER IF NOT EXISTS 'datapilot_reader'@'%' IDENTIFIED BY '${DATAPILOT_DB_READ_PASSWORD}' WITH MAX_USER_CONNECTIONS 12;
ALTER USER 'datapilot_reader'@'%' IDENTIFIED BY '${DATAPILOT_DB_READ_PASSWORD}' WITH MAX_USER_CONNECTIONS 12;
	GRANT SELECT ON datapilot.* TO 'datapilot_reader'@'%';
CREATE USER IF NOT EXISTS 'datapilot_seed'@'%' IDENTIFIED BY '${DATAPILOT_DB_SEED_PASSWORD}' WITH MAX_USER_CONNECTIONS 2;
ALTER USER 'datapilot_seed'@'%' IDENTIFIED BY '${DATAPILOT_DB_SEED_PASSWORD}' WITH MAX_USER_CONNECTIONS 2;
	GRANT SELECT, INSERT, UPDATE, DELETE ON datapilot.* TO 'datapilot_seed'@'%';
EOSQL
