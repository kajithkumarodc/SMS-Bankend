# SMS Backend

This project is the Spring Boot backend for a multi-tenant School Management SaaS platform.

## Prerequisites

- Java 21
- Maven 3.9+
- PostgreSQL 14+ running locally

## Local setup

1. Start PostgreSQL and create the database and user:

   ```sql
   CREATE DATABASE sms_db;
   CREATE USER sms_user WITH PASSWORD 'change-me';
   GRANT ALL PRIVILEGES ON DATABASE sms_db TO sms_user;
   ```

2. Set environment variables:

   ```bash
   export POSTGRES_DB=sms_db
   export POSTGRES_USER=sms_user
   export POSTGRES_PASSWORD=change-me
   export JWT_ISSUER_URI=http://localhost:8080
   ```

   On Windows PowerShell:

   ```powershell
   $env:POSTGRES_DB = "sms_db"
   $env:POSTGRES_USER = "sms_user"
   $env:POSTGRES_PASSWORD = "change-me"
   $env:JWT_ISSUER_URI = "http://localhost:8080"
   ```

3. Run the application:

   ```bash
   mvn clean spring-boot:run
   ```

4. Open the API docs:

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
