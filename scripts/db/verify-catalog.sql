\set ON_ERROR_STOP on
DO $$
BEGIN
  IF (SELECT count(*) FROM flyway.flyway_schema_history WHERE success AND version IS NOT NULL) <> 5 THEN
    RAISE EXCEPTION 'Expected five successful migrations';
  END IF;
  IF (SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tableowner = 'parking_migrator') <> 25 THEN
    RAISE EXCEPTION 'Expected 25 application tables owned by migrator';
  END IF;
  IF (SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
      WHERE c.relowner = 'parking_migrator'::regrole AND NOT t.tgisinternal) <> 8 THEN
    RAISE EXCEPTION 'Expected eight application triggers';
  END IF;
  IF (SELECT count(*) FROM pg_indexes WHERE schemaname = 'public' AND indexdef LIKE '%USING gist%'
      AND tablename <> 'spatial_ref_sys') <> 6 THEN
    RAISE EXCEPTION 'Expected six spatial indexes';
  END IF;
  IF (SELECT count(*) FROM pg_indexes WHERE indexname IN
      ('uq_vehicles_active_user', 'uq_prediction_versions_active', 'uq_data_imports_active_source')
      AND indexdef LIKE '%UNIQUE%WHERE%') <> 3 THEN
    RAISE EXCEPTION 'Expected three partial unique indexes';
  END IF;
  IF (SELECT count(*) FROM pg_trigger WHERE tgname IN ('ctr_ra_reason_required', 'ctr_ar_reason_required')
      AND tgdeferrable AND tginitdeferred) <> 2 THEN
    RAISE EXCEPTION 'Representative evidence must be checked at commit';
  END IF;
  IF (SELECT proowner FROM pg_proc WHERE oid = 'public.purge_prediction_version(uuid)'::regprocedure)
      <> 'parking_migrator'::regrole THEN
    RAISE EXCEPTION 'Purge must belong to the isolated migration owner';
  END IF;
  IF has_function_privilege('parking_api', 'public.purge_prediction_version(uuid)', 'EXECUTE')
      OR has_function_privilege('parking_loader', 'public.purge_prediction_version(uuid)', 'EXECUTE')
      OR NOT has_function_privilege('parking_operator', 'public.purge_prediction_version(uuid)', 'EXECUTE')
      OR has_schema_privilege('parking_api', 'public', 'CREATE')
      OR pg_has_role('parking_api', 'parking_migrator', 'MEMBER')
      OR pg_has_role('parking_loader', 'parking_migrator', 'MEMBER') THEN
    RAISE EXCEPTION 'Runtime/ETL/admin privilege separation failed';
  END IF;
END $$;
SELECT version, description, success FROM flyway.flyway_schema_history ORDER BY installed_rank;
SELECT version(), postgis_full_version();
SELECT c.contype, count(*) FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
WHERE t.relowner = 'parking_migrator'::regrole AND t.relnamespace = 'public'::regnamespace
GROUP BY c.contype ORDER BY c.contype;
SELECT p.proname, r.rolname AS owner, p.prosecdef AS security_definer
FROM pg_proc p JOIN pg_roles r ON r.oid = p.proowner
WHERE p.pronamespace = 'public'::regnamespace AND r.rolname = 'parking_migrator'
ORDER BY p.proname;
