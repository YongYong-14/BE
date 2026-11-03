\set ON_ERROR_STOP on
DO $$ DECLARE actual jsonb; expected jsonb; BEGIN
  SELECT jsonb_agg(to_jsonb(p) - ARRAY['fee_type','weekday_open','weekday_close',
      'saturday_open','saturday_close','holiday_open','holiday_close'] ORDER BY id)
    INTO actual FROM parking_lots p;
  SELECT jsonb_agg(row - ARRAY['is_paid','operating_hours'] ORDER BY row->>'id') INTO expected
    FROM jsonb_array_elements((SELECT value FROM verification.snapshots WHERE kind='rows')) row;
  IF actual IS DISTINCT FROM expected THEN RAISE EXCEPTION 'Unrelated parking data changed'; END IF;
  IF (SELECT count(*) FROM parking_lots) <> 3
     OR NOT EXISTS (SELECT 1 FROM parking_lots WHERE source_record_key='1' AND fee_type='PAID')
     OR NOT EXISTS (SELECT 1 FROM parking_lots WHERE source_record_key='2' AND fee_type='FREE')
     OR NOT EXISTS (SELECT 1 FROM parking_lots WHERE source_record_key='3' AND fee_type IS NULL)
     OR EXISTS (SELECT 1 FROM parking_lots WHERE weekday_open IS NOT NULL OR weekday_close IS NOT NULL
       OR saturday_open IS NOT NULL OR saturday_close IS NOT NULL OR holiday_open IS NOT NULL OR holiday_close IS NOT NULL) THEN
    RAISE EXCEPTION 'Boolean backfill or nullable hours failed';
  END IF;
  SELECT jsonb_build_object('oid',oid,'owner',relowner,'acl',relacl::text) INTO actual
    FROM pg_class WHERE oid='public.parking_lots'::regclass;
  IF actual IS DISTINCT FROM (SELECT value FROM verification.snapshots WHERE kind='table') THEN
    RAISE EXCEPTION 'Table identity/owner/grants changed';
  END IF;
  SELECT jsonb_agg(jsonb_build_object('oid',indexrelid,'definition',pg_get_indexdef(indexrelid)) ORDER BY indexrelid)
    INTO actual FROM pg_index WHERE indrelid='public.parking_lots'::regclass;
  IF actual IS DISTINCT FROM (SELECT value FROM verification.snapshots WHERE kind='indexes') THEN
    RAISE EXCEPTION 'PK/unique/spatial indexes changed';
  END IF;
  SELECT jsonb_agg(jsonb_build_object('oid',oid,'name',conname,'definition',pg_get_constraintdef(oid)) ORDER BY oid)
    INTO actual FROM pg_constraint WHERE conrelid='public.parking_lots'::regclass AND contype <> 'c';
  IF actual IS DISTINCT FROM (SELECT value FROM verification.snapshots WHERE kind='constraints') THEN
    RAISE EXCEPTION 'Existing PK/FK/unique constraints changed';
  END IF;
END $$;
\echo PASS: true/false/null backfill, original data, NULL hours, table/constraint/index identities and grants
