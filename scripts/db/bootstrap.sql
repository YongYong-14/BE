-- Run with psql as the database administrator in the intended database.
-- Passwords are deliberately absent. Use psql \password for each login afterward.
\set ON_ERROR_STOP on
CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'parking_app') THEN
    CREATE ROLE parking_app NOLOGIN;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'parking_etl') THEN
    CREATE ROLE parking_etl NOLOGIN;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'parking_admin') THEN
    CREATE ROLE parking_admin NOLOGIN;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'parking_migrator') THEN
    CREATE ROLE parking_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'parking_api') THEN
    CREATE ROLE parking_api LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION IN ROLE parking_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'parking_loader') THEN
    CREATE ROLE parking_loader LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION IN ROLE parking_etl;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'parking_operator') THEN
    CREATE ROLE parking_operator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION IN ROLE parking_admin;
  END IF;
  IF EXISTS (
    SELECT 1 FROM pg_roles
    WHERE rolname IN ('parking_api', 'parking_loader', 'parking_operator',
                      'parking_app', 'parking_etl', 'parking_admin', 'parking_migrator')
      AND (rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls)
  ) THEN
    RAISE EXCEPTION 'Unsafe existing parking role attributes; administrator must review';
  END IF;
  IF EXISTS (
    SELECT 1 FROM pg_roles
    WHERE rolname IN ('parking_api', 'parking_loader', 'parking_operator',
                      'parking_app', 'parking_etl', 'parking_admin')
      AND pg_has_role(oid, 'parking_migrator', 'MEMBER')
  ) THEN
    RAISE EXCEPTION 'Runtime/ETL/admin roles must not be members of parking_migrator';
  END IF;
END $$;
-- Role membership needs ADMIN OPTION; this belongs to bootstrap, not Flyway.
GRANT parking_etl TO parking_admin;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO parking_migrator;
SELECT format('GRANT CONNECT, CREATE ON DATABASE %I TO parking_migrator', current_database()) \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO parking_api, parking_loader, parking_operator', current_database()) \gexec
-- Never grant parking_migrator (or the bootstrap superuser) to app/etl/admin logins.
