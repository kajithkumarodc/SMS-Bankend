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

## Payments (Razorpay)

Fee collection integrates [Razorpay](https://razorpay.com/) in **test mode**. All
credentials come from the environment only (never hard-coded — plan section 7.2d):

| Variable | Purpose |
|---|---|
| `RAZORPAY_KEY_ID` | Public key id. Sent to the browser Checkout widget; safe to expose. |
| `RAZORPAY_KEY_SECRET` | Secret key. Used server-side only, to create payment Orders. |
| `RAZORPAY_WEBHOOK_SECRET` | Separate secret configured on the Razorpay dashboard webhook. Used only to verify the `X-Razorpay-Signature` on inbound webhooks. |
| `RAZORPAY_CURRENCY` | ISO currency code for new orders (default `INR`). |

Flow:

1. A `SCHOOL_ADMIN` defines a **fee structure** (`POST /api/v1/fee-structures`) and
   generates a per-student **invoice** (`POST /api/v1/invoices`), which starts `PENDING`.
2. `POST /api/v1/invoices/{invoiceId}/checkout` creates a Razorpay Order server-side
   and returns only the order id, public key id and amount — never any raw payment
   data (plan section 7.2a, tokenization).
3. The browser completes payment with Razorpay Checkout. Razorpay then calls
   `POST /api/v1/webhooks/razorpay` (server-to-server). This endpoint is public (no
   JWT) and is authenticated **solely** by its HMAC signature: a missing or invalid
   signature is rejected with `400` before the body is read. A signature-verified
   `order.paid` event flips the matching invoice to `PAID` (idempotent — a Razorpay
   retry is a no-op).

### Receiving webhooks locally with ngrok

Razorpay calls the webhook server-to-server, so `localhost` is not reachable from
it. Expose the running backend with a tunnel.

1. **Install ngrok** (free, one-time):

   ```powershell
   winget install Ngrok.Ngrok
   ```

   or download from <https://ngrok.com/download>. Then create a free account at
   <https://dashboard.ngrok.com>, copy your authtoken, and register it once:

   ```bash
   ngrok config add-authtoken <YOUR_AUTHTOKEN>
   ```

2. **Start the tunnel** to the backend port (this project runs it on `8081`
   locally — `mvn spring-boot:run "-Dspring-boot.run.arguments=--server.port=8081"`):

   ```bash
   ngrok http 8081
   ```

   ngrok prints a forwarding line like
   `Forwarding  https://a1b2-c3d4.ngrok-free.app -> http://localhost:8081`.

3. **Configure the webhook in the Razorpay Dashboard** — *Settings → Webhooks →
   Add New Webhook*:

   | Field | Value |
   |---|---|
   | Webhook URL | `https://<your-ngrok-subdomain>.ngrok-free.app/api/v1/webhooks/razorpay` |
   | Secret | the exact value of `RAZORPAY_WEBHOOK_SECRET` in your `.env` |
   | Active Events | tick **`order.paid`** |

   Save. Razorpay sends a test ping; the endpoint returns `200` for a correctly
   signed request and `400` for anything unsigned or tampered.

> **Free-tier URL is ephemeral.** ngrok assigns a new `*.ngrok-free.app`
> subdomain every time you restart it, so you must update the webhook URL in the
> Razorpay Dashboard each session. To avoid that, claim the one free static
> domain (dashboard → *Domains*) and start the tunnel with it:
> `ngrok http --domain=<your-name>.ngrok-free.app 8081` — then the Razorpay URL
> never changes. (ngrok ≥ 3.5 renamed this flag to `--url`; `--domain` still works.)

### Local demo without a webhook tunnel (dev-tools) — ⚠️ never deploy this

When a public tunnel isn't available, a **local-development-only** endpoint can
flip an invoice to `PAID` the same way the verified webhook would, so a demo can
show the paid state:

```
POST /api/v1/dev/invoices/{invoiceId}/simulate-payment-success
```

A `SCHOOL_ADMIN` may call it for any invoice in the tenant; a `PARENT` only for
their own child's — this is what lets the parent-facing **"Pay now"** flow work
locally: the frontend opens the real Razorpay Checkout widget (test mode), and on
the widget's success callback calls this endpoint to stand in for the webhook.

It is gated by `app.dev-tools-enabled`, which **defaults to `false`**. When false
or absent, `DevToolsController` is not even registered as a bean — the route does
not exist and returns `404` (covered by `DevToolsDisabledTest`, which pins the
flag off regardless of `.env`). To use it locally, set `APP_DEV_TOOLS_ENABLED=true`
(or `app.dev-tools-enabled: true`) and restart, then:

```bash
curl -X POST http://localhost:8081/api/v1/dev/invoices/<invoiceId>/simulate-payment-success \
  -b "access_token=<admin JWT cookie>"
```

**This is a temporary convenience and must never be enabled in any deployed
environment.** Real payment confirmation must always arrive through the
signature-verified Razorpay webhook (`POST /api/v1/webhooks/razorpay`) — never
through this endpoint. Before any real deployment: keep `app.dev-tools-enabled`
`false`, delete `DevToolsController` + `FeeService.simulatePaymentSuccess`, and
drop the `simulateInvoicePayment` call from the frontend `PayNowButton` (the
widget's success callback then just waits for the webhook). Do not "secure" it and
ship it — a payment side-channel that bypasses gateway verification does not
belong in production even behind auth.

Parents read their own children's invoices at
`GET /api/v1/me/children/{studentId}/invoices` (ownership-scoped, like the other
`/api/v1/me/...` endpoints).

The Razorpay HTTP client is never called from tests — `RazorpayGateway` is mocked —
but webhook signature verification is exercised for real against a test secret.

## Reporting (school-level)

First slice of school-level reporting (plan section 2), read directly off the
operational tables as simple tenant-scoped aggregate queries — no separate
analytics store. All three are **SCHOOL_ADMIN only**:

| Endpoint | Returns |
|---|---|
| `GET /api/v1/reports/attendance-trend?from={date}&to={date}` | one row per day in the inclusive range: `present` / `absent` / `late` counts, `total`, and `attendancePercentage` (`(present + late) / total`, 2 dp) |
| `GET /api/v1/reports/academic-performance?classId={id}` | one row per exam of that class that has marks: `averageMarks`, `studentsGraded`, oldest exam first |
| `GET /api/v1/reports/fee-collection` | `totalInvoiced`, `totalCollected` (PAID), `outstanding`, and `overdueInvoices` — the defaulter list of still-`PENDING` invoices whose due date has passed |

Teacher-scoped and parent-scoped reports are a later slice.

## Announcements

School-wide messages (plan section 2, "Communication"). Tenant-scoped: everyone
signed in to the school reads the same list; only a `SCHOOL_ADMIN` writes.

| Endpoint | Access |
|---|---|
| `POST /api/v1/announcements` (`{title, body}`) | SCHOOL_ADMIN only |
| `GET /api/v1/announcements` (paginated, newest first) | any authenticated role — admin, teacher, student, parent |
| `DELETE /api/v1/announcements/{id}` | SCHOOL_ADMIN only; 404 for a cross-tenant id |

`GET /api/v1/dashboard/summary` also carries the **3 most recent** announcements
in an `announcements` array, for every role (genuinely tenant-wide, not
ownership-scoped).

## Library

First slice of the library module (plan section 2) — catalog plus issue/return.
Fines and reservations are a later slice. Every book and loan is tenant-scoped on
top of RLS; `available_copies` moves with the loans (−1 on issue, +1 on return,
capped at `total_copies`). The standard loan period is 14 days.

| Endpoint | Access |
|---|---|
| `POST /api/v1/library/books` (`{title, author, isbn?, totalCopies}`) | SCHOOL_ADMIN only; `available_copies` starts equal to `totalCopies` |
| `GET /api/v1/library/books?q={text}` (paginated, title asc) | any authenticated role; `q` matches title or author |
| `POST /api/v1/library/loans` (`{bookId, studentId}`) | SCHOOL_ADMIN only; 404 if the book/student is not in the tenant, 400 if no copies are available |
| `POST /api/v1/library/loans/{loanId}/return` | SCHOOL_ADMIN only; 404 for a cross-tenant id, 409 if already returned |
| `GET /api/v1/library/loans?studentId={id}` | SCHOOL_ADMIN or TEACHER; 404 if the student is not in the tenant |
| `GET /api/v1/library/loans/active` | SCHOOL_ADMIN or TEACHER; every not-yet-returned loan in the tenant (book title + student name), soonest due first |
| `GET /api/v1/me/student/library` | STUDENT — their own loan history only |
| `GET /api/v1/me/children/{studentId}/library` | PARENT — their own child's loan history only (404 otherwise) |

## Transport

First slice of the transport module (plan section 2) — routes, vehicles and
student-route assignment. Stops, fee integration and GPS/live-tracking are later
considerations. Every route and vehicle is tenant-scoped on top of RLS;
`(tenant_id, registration_number)` is unique so two tenants may reuse a plate.
A student optionally carries a `transport_route_id`.

| Endpoint | Access |
|---|---|
| `POST /api/v1/transport/routes` (`{name}`) | SCHOOL_ADMIN only |
| `GET /api/v1/transport/routes` | any authenticated role; tenant's routes by name |
| `POST /api/v1/transport/vehicles` (`{registrationNumber, driverName, driverContact?, capacity, routeId?}`) | SCHOOL_ADMIN only; 404 if `routeId` is not in the tenant, 409 on a duplicate registration number |
| `GET /api/v1/transport/vehicles?routeId={id}` | any authenticated role; optionally filtered to one route |
| `PATCH /api/v1/students/{id}/transport-route` (`{routeId}`, null to unassign) | SCHOOL_ADMIN only; 404 if the student or route is not in the tenant |
| `GET /api/v1/transport/routes/{routeId}/students` | SCHOOL_ADMIN or TEACHER; 404 for a cross-tenant route |
| `GET /api/v1/me/student/transport` | STUDENT — their own route + vehicle + driver info; 404 if not assigned |
| `GET /api/v1/me/children/{studentId}/transport` | PARENT — their own child's assignment (404 otherwise) |

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
