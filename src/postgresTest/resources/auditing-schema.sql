-- Test-only tables; provisioned after DB identity verification, never via Flyway.
CREATE SCHEMA auditing_test;
CREATE TABLE auditing_test.audit_created (
    id bigint PRIMARY KEY,
    label varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE auditing_test.audit_time (
    id uuid PRIMARY KEY,
    label varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
