\set ON_ERROR_STOP on
-- Catalog checks run in both fresh and upgraded databases.
DO $$ DECLARE col text; BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public'
      AND table_name='parking_lots' AND column_name IN ('is_paid','operating_hours')) THEN
    RAISE EXCEPTION 'Old columns still exist';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='parking_lots'
      AND column_name='fee_type' AND data_type='character varying' AND character_maximum_length=10
      AND is_nullable='YES' AND column_default IS NULL) THEN
    RAISE EXCEPTION 'fee_type metadata mismatch';
  END IF;
  FOREACH col IN ARRAY ARRAY['weekday_open','weekday_close','saturday_open','saturday_close','holiday_open','holiday_close'] LOOP
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='parking_lots'
        AND column_name=col AND data_type='time without time zone' AND is_nullable='YES' AND column_default IS NULL) THEN
      RAISE EXCEPTION 'Hours metadata mismatch: %', col;
    END IF;
  END LOOP;
  IF (SELECT count(*) FROM pg_constraint WHERE conrelid='public.parking_lots'::regclass AND contype='c'
      AND conname IN ('ck_pl_fee_type','ck_pl_weekday_pair','ck_pl_saturday_pair','ck_pl_holiday_pair') AND convalidated) <> 4 THEN
    RAISE EXCEPTION 'Expected four validated parking CHECKs';
  END IF;
  IF NOT has_table_privilege('parking_api','public.parking_lots','SELECT')
     OR has_table_privilege('parking_api','public.parking_lots','INSERT')
     OR NOT has_table_privilege('parking_loader','public.parking_lots','INSERT,UPDATE,DELETE') THEN
    RAISE EXCEPTION 'Parking role privileges changed';
  END IF;
END $$;
BEGIN;
SET ROLE parking_loader;
DO $$ DECLARE imp uuid := gen_random_uuid(); lot uuid := gen_random_uuid(); day text; fee text; got text; side text; BEGIN
  INSERT INTO data_imports(id,source_dataset,file_name,file_hash) VALUES (imp,'parking-check','test',imp::text);
  INSERT INTO parking_lots(id,import_id,source_record_key,name,location)
    VALUES (lot,imp,'test','test','SRID=4326;POINT(127 37)');
  FOREACH fee IN ARRAY ARRAY['FREE','PAID','MIXED',NULL] LOOP
    UPDATE parking_lots SET fee_type=fee WHERE id=lot;
  END LOOP;
  BEGIN
    UPDATE parking_lots SET fee_type='INVALID' WHERE id=lot;
    RAISE EXCEPTION 'Invalid fee accepted';
  EXCEPTION WHEN check_violation THEN
    GET STACKED DIAGNOSTICS got = CONSTRAINT_NAME;
    IF got <> 'ck_pl_fee_type' THEN RAISE; END IF;
  END;
  FOREACH day IN ARRAY ARRAY['weekday','saturday','holiday'] LOOP
    FOREACH side IN ARRAY ARRAY['open','close'] LOOP
      BEGIN
        EXECUTE format('UPDATE parking_lots SET %I=TIME ''09:00'' WHERE id=$1',day||'_'||side) USING lot;
        RAISE EXCEPTION 'Incomplete hours pair accepted: % %',day,side;
      EXCEPTION WHEN check_violation THEN
        GET STACKED DIAGNOSTICS got = CONSTRAINT_NAME;
        IF got <> 'ck_pl_'||day||'_pair' THEN RAISE; END IF;
      END;
    END LOOP;
    -- Normal, overnight, equal endpoints (meaning unresolved), 24h display convention, NULL.
    EXECUTE format('UPDATE parking_lots SET %I=TIME ''09:00'',%I=TIME ''18:00'' WHERE id=$1',day||'_open',day||'_close') USING lot;
    EXECUTE format('UPDATE parking_lots SET %I=TIME ''22:00'',%I=TIME ''02:00'' WHERE id=$1',day||'_open',day||'_close') USING lot;
    EXECUTE format('UPDATE parking_lots SET %I=TIME ''09:00'',%I=TIME ''09:00'' WHERE id=$1',day||'_open',day||'_close') USING lot;
    EXECUTE format('UPDATE parking_lots SET %I=TIME ''00:00'',%I=TIME ''23:59'' WHERE id=$1',day||'_open',day||'_close') USING lot;
    EXECUTE format('UPDATE parking_lots SET %I=NULL,%I=NULL WHERE id=$1',day||'_open',day||'_close') USING lot;
  END LOOP;
END $$;
ROLLBACK;
\echo PASS: parking column types/nullability, CHECKs, fee domain, all six invalid half-pairs, valid/NULL/overnight times, roles
