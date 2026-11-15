\set ON_ERROR_STOP on
-- Isolated V4 database only. One row per old boolean value, hours deliberately NULL.
INSERT INTO data_imports(id,source_dataset,file_name,file_hash)
VALUES ('00000000-0000-0000-0000-000000007501','parking-v5-test','fixture.csv','parking-v5-fixture');
INSERT INTO parking_lots(id,import_id,source_record_key,name,location,is_paid,fee_info,address,capacity)
SELECT ('00000000-0000-0000-0000-00000000750' || n)::uuid,
 '00000000-0000-0000-0000-000000007501', n::text, 'fixture-' || n,
 'SRID=4326;POINT(127 37)', CASE n WHEN 1 THEN true WHEN 2 THEN false ELSE NULL END,
 'original fee details', 'original address', 10
FROM generate_series(1,3) n;
CREATE SCHEMA verification;
CREATE TABLE verification.snapshots(kind text PRIMARY KEY, value jsonb NOT NULL);
INSERT INTO verification.snapshots VALUES
 ('rows', (SELECT jsonb_agg(to_jsonb(p) ORDER BY id) FROM parking_lots p)),
 ('table', (SELECT jsonb_build_object('oid',oid,'owner',relowner,'acl',relacl::text)
             FROM pg_class WHERE oid='public.parking_lots'::regclass)),
 ('indexes', (SELECT jsonb_agg(jsonb_build_object('oid',indexrelid,'definition',pg_get_indexdef(indexrelid)) ORDER BY indexrelid)
             FROM pg_index WHERE indrelid='public.parking_lots'::regclass)),
 ('constraints', (SELECT jsonb_agg(jsonb_build_object('oid',oid,'name',conname,'definition',pg_get_constraintdef(oid)) ORDER BY oid)
             FROM pg_constraint WHERE conrelid='public.parking_lots'::regclass)),
 ('columns', (SELECT jsonb_agg(jsonb_build_object('name',attname,'type',format_type(atttypid,atttypmod),'notnull',attnotnull,'acl',attacl::text) ORDER BY attnum)
             FROM pg_attribute WHERE attrelid='public.parking_lots'::regclass AND attnum>0 AND NOT attisdropped));
-- The project has table grants, no explicit column ACLs or objects depending on old columns.
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='public.parking_lots'::regclass AND attacl IS NOT NULL)
     OR EXISTS (SELECT 1 FROM pg_depend d JOIN pg_attribute a
       ON a.attrelid=d.refobjid AND a.attnum=d.refobjsubid
       WHERE d.refclassid='pg_class'::regclass AND a.attrelid='public.parking_lots'::regclass
         AND a.attname IN ('is_paid','operating_hours')) THEN
    RAISE EXCEPTION 'Unexpected column grants/dependencies in V4';
  END IF;
END $$;
