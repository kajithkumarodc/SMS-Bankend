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

6. Set application environment variables if needed:

   ```bash
   export DB_URL=jdbc:postgresql://localhost:5433/sms_db
   export DB_USERNAME=sms_user
   export DB_PASSWORD=sms_pass
   export JWT_ISSUER_URI=http://localhost:8080
   ```

   On Windows PowerShell:

   ```powershell
   $env:DB_URL = "jdbc:postgresql://localhost:5433/sms_db"
   $env:DB_USERNAME = "sms_user"
   $env:DB_PASSWORD = "sms_pass"
   $env:JWT_ISSUER_URI = "http://localhost:8080"
   ```

7. Run the application:

   ```bash
   mvn clean spring-boot:run
   ```

8. Open the API docs:

   - Swagger UI: http://localhost:8080/swagger-ui.html
   - OpenAPI JSON: http://localhost:8080/v3/api-docs

## Build

```bash
mvn clean install
```

## Project structure

- `src/main/java/com/smsapp` – application code
- `src/main/resources` – configuration and static resources
- `src/main/resources/db/migration` – Flyway migration scripts
- `src/test` – test sources
