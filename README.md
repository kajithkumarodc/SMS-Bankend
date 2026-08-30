# SMS Backend

This project is the Spring Boot backend for a multi-tenant School Management SaaS platform.

## Prerequisites

- Java 21
- Maven 3.9+
- PostgreSQL 17 running natively on Windows at `localhost:5433`
- Docker Desktop with Docker Compose (for Redis, RabbitMQ, and MinIO)

## Local setup

1. Ensure the native PostgreSQL 17 instance is running on port `5433` and that the `sms_db` database exists. Port `5432` belongs to a separate PostgreSQL 18 instance and is not used by this application.

2. Create the local environment file if it does not already exist:

   ```powershell
   Copy-Item .env.example .env
   ```

   Spring Boot automatically loads the root `.env` file. The current local values should point to the native PostgreSQL 17 instance with `DB_URL=jdbc:postgresql://localhost:5433/sms_db`. Keep `.env` private; it is excluded by `.gitignore`.

3. Start the local supporting infrastructure stack:

   ```bash
   docker compose up -d
   ```

   PostgreSQL is provided by the native Windows installation on `localhost:5433`. Docker Compose provides Redis at `localhost:6379`, RabbitMQ at `localhost:5672` with management UI at `http://localhost:15672`, and MinIO at `http://localhost:9000` with console at `http://localhost:9001`.

4. Check Docker service status:

   ```bash
   docker compose ps
   ```

5. Stop the local supporting infrastructure stack:

   ```bash
   docker compose down
   ```

   Named volumes preserve data between restarts. To remove the persisted data too, run `docker compose down -v`.

6. Set application environment variables if needed (see `.env.example` for the full
   list). The database uses **two separate connections** — see
   [Database roles & Row-Level Security](#database-roles--row-level-security):

   ```bash
   export DB_URL=jdbc:postgresql://localhost:5433/sms_db
   export DB_USERNAME=postgres          # Flyway migrations only (elevated)
   export DB_PASSWORD=postgres_password
   export DB_APP_USERNAME=app_user      # application runtime pool (restricted)
   export DB_APP_PASSWORD=app_user_password
   export JWT_ISSUER_URI=http://localhost:8080
   ```

   On Windows PowerShell:

   ```powershell
   $env:DB_URL = "jdbc:postgresql://localhost:5433/sms_db"
   $env:DB_USERNAME = "postgres"
   $env:DB_PASSWORD = "postgres_password"
   $env:DB_APP_USERNAME = "app_user"
   $env:DB_APP_PASSWORD = "app_user_password"
   $env:JWT_ISSUER_URI = "http://localhost:8080"
   ```

   You do **not** need to create `app_user` by hand — migration `V4` provisions it
   (using `DB_APP_USERNAME` / `DB_APP_PASSWORD`) the first time Flyway runs.

7. Run the application:

   ```bash
   mvn clean spring-boot:run
   ```

8. Open the API docs:

   - Swagger UI: http://localhost:8080/swagger-ui.html
   - OpenAPI JSON: http://localhost:8080/v3/api-docs

## Database roles & Row-Level Security

Multi-tenant isolation is enforced in the database with PostgreSQL Row-Level
Security (RLS): every tenant-scoped table has a policy of the form
`USING (tenant_id::text = current_setting('app.current_tenant_id', true))`, and
each request/transaction sets `app.current_tenant_id` from the caller's JWT.
**RLS is ignored for superusers and for any role with `BYPASSRLS`**, so the
application must never connect as one.

The backend therefore uses two distinct database connections:

| Connection | Role | Privileges | Used for |
|---|---|---|---|
| Flyway migrations (`spring.flyway.*`, `DB_USERNAME` / `DB_PASSWORD`) | e.g. `postgres` | elevated — `CREATE TABLE`, `ALTER TABLE`, `CREATE ROLE`, policy management | schema migrations **only**, at startup |
| Application runtime pool (HikariCP) (`spring.datasource.*`, `DB_APP_USERNAME` / `DB_APP_PASSWORD`) | `app_user` | `SELECT, INSERT, UPDATE, DELETE` on the application tables — **`NOSUPERUSER`, `NOBYPASSRLS`, `NOCREATEDB`, `NOCREATEROLE`** | every runtime query |

`app_user` is created and granted by migration `V4__restricted_app_runtime_role.sql`,
which runs on the elevated Flyway connection (it needs `CREATE ROLE`). Its password
comes from the `DB_APP_PASSWORD` environment variable via a Flyway placeholder, so
no secret is committed. Future migrations that add tables are covered by
`ALTER DEFAULT PRIVILEGES`.

**Rules:**

- The application's runtime connection pool must **never** use a superuser or a
  `BYPASSRLS` role. Point `DB_APP_USERNAME` at `app_user` (or an equivalently
  restricted role), never at `postgres`.
- Flyway legitimately needs the elevated connection (schema DDL, `CREATE ROLE`);
  that is expected and is the only place a privileged account is used.
- PostgreSQL roles are cluster-wide. The integration tests use a separately named
  runtime role (`app_user_test`) so the dev and test databases don't clash over
  one role's password.
- `is_superuser` / RLS enforcement on the running app can be checked with:
  `SELECT usename, application_name FROM pg_stat_activity WHERE datname = 'sms_db';`
  — the `PostgreSQL JDBC Driver` connections must all be `app_user`.

## Build

```bash
mvn clean install
```

## Phase 1 tests

The integration suite temporarily uses the isolated native PostgreSQL 17 test database `sms_db_test` on port `5433`, never the development database `sms_db`. Create it once as a PostgreSQL administrator:

```sql
CREATE DATABASE sms_db_test;
```

The test profile runs Flyway against this database as the elevated user and connects
the runtime pool as the restricted `app_user_test` role (provisioned by `V4`), so the
integration suite exercises RLS against the same kind of identity production uses.
Override `DB_TEST_USERNAME` / `DB_TEST_PASSWORD` (Flyway) and
`DB_TEST_APP_USERNAME` / `DB_TEST_APP_PASSWORD` (runtime) if your local defaults
differ. The test base is intentionally kept easy to switch back to Testcontainers
when Docker is available; Testcontainers provides cleaner isolation for team environments.

Run all checks with:

```bash
mvn clean test
```

## Project structure

- `src/main/java/com/smsapp` – application code
- `src/main/resources` – configuration and static resources
- `src/main/resources/db/migration` – Flyway migration scripts
- `src/test` – test sources
