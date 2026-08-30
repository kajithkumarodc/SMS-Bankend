-- The application's runtime connection pool must NOT use a superuser. A superuser
-- (and any role with BYPASSRLS) skips Row-Level Security entirely, which defeats
-- the tenant-isolation defense-in-depth described in plan section 1 ("use both
-- together"). This migration provisions a restricted login role that holds only
-- the DML the application performs.
--
-- Flyway itself keeps running as the elevated migration user (it needs DDL and
-- CREATE ROLE privileges) -- see spring.flyway.* in application.yml. That is the
-- only place a privileged database account is used.
--
-- The password is supplied through the Flyway placeholder `app_user_password`
-- (spring.flyway.placeholders.app_user_password <- DB_APP_PASSWORD), so no secret
-- is committed to source control.

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${app_user_name}') THEN
        ALTER ROLE ${app_user_name} WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS
            PASSWORD '${app_user_password}';
    ELSE
        CREATE ROLE ${app_user_name} WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS
            PASSWORD '${app_user_password}';
    END IF;
END
$$;

-- Allow the role to connect to whichever database this migration runs against.
DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO ${app_user_name}', current_database());
END
$$;

GRANT USAGE ON SCHEMA public TO ${app_user_name};

-- Exactly the DML the application performs, on the current application tables...
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ${app_user_name};

-- ...and on tables created by future migrations (owned by the migration user).
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${app_user_name};

-- The application never reads or writes Flyway's own bookkeeping table.
REVOKE ALL ON TABLE flyway_schema_history FROM ${app_user_name};
