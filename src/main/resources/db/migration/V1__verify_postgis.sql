-- PostGIS must be installed by the administrator, not the API or migration login.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = current_user AND (rolsuper OR rolcreaterole))
     OR pg_has_role('parking_app', current_user, 'MEMBER')
     OR pg_has_role('parking_etl', current_user, 'MEMBER')
     OR pg_has_role('parking_admin', current_user, 'MEMBER') THEN
    RAISE EXCEPTION 'Flyway requires a separate non-superuser owner that app/etl/admin cannot assume';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
    WHERE e.extname = 'postgis' AND n.nspname = 'public'
      AND e.extversion LIKE '3.6%'
  ) THEN
    RAISE EXCEPTION 'PostGIS 3.6 in public is required; run scripts/db/bootstrap.sql as administrator first';
  END IF;
END $$;
