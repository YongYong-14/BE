\set ON_ERROR_STOP on
DO $$ DECLARE actual jsonb; BEGIN
  IF (SELECT count(*) FROM flyway.flyway_schema_history WHERE version IS NOT NULL AND success) <> 4
     OR EXISTS (SELECT 1 FROM flyway.flyway_schema_history WHERE version='5') THEN
    RAISE EXCEPTION 'Blocked migration changed history';
  END IF;
  SELECT jsonb_agg(to_jsonb(p) ORDER BY id) INTO actual FROM parking_lots p;
  IF actual IS DISTINCT FROM (SELECT value FROM verification.snapshots WHERE kind='rows') THEN
    RAISE EXCEPTION 'Blocked migration changed data';
  END IF;
  SELECT jsonb_agg(jsonb_build_object('name',attname,'type',format_type(atttypid,atttypmod),'notnull',attnotnull,'acl',attacl::text) ORDER BY attnum)
    INTO actual FROM pg_attribute WHERE attrelid='public.parking_lots'::regclass AND attnum>0 AND NOT attisdropped;
  IF actual IS DISTINCT FROM (SELECT value FROM verification.snapshots WHERE kind='columns') THEN
    RAISE EXCEPTION 'Blocked migration left column changes';
  END IF;
  SELECT jsonb_build_object('oid',oid,'owner',relowner,'acl',relacl::text) INTO actual
    FROM pg_class WHERE oid='public.parking_lots'::regclass;
  IF actual IS DISTINCT FROM (SELECT value FROM verification.snapshots WHERE kind='table') THEN
    RAISE EXCEPTION 'Blocked migration changed table/grants';
  END IF;
  SELECT jsonb_agg(jsonb_build_object('oid',indexrelid,'definition',pg_get_indexdef(indexrelid)) ORDER BY indexrelid)
    INTO actual FROM pg_index WHERE indrelid='public.parking_lots'::regclass;
  IF actual IS DISTINCT FROM (SELECT value FROM verification.snapshots WHERE kind='indexes') THEN
    RAISE EXCEPTION 'Blocked migration changed indexes';
  END IF;
  SELECT jsonb_agg(jsonb_build_object('oid',oid,'name',conname,'definition',pg_get_constraintdef(oid)) ORDER BY oid)
    INTO actual FROM pg_constraint WHERE conrelid='public.parking_lots'::regclass;
  IF actual IS DISTINCT FROM (SELECT value FROM verification.snapshots WHERE kind='constraints') THEN
    RAISE EXCEPTION 'Blocked migration changed constraints';
  END IF;
END $$;
\echo PASS: blocked V5 left V4 columns, every row, constraints, indexes, ACL and history unchanged
