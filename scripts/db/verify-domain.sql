\set ON_ERROR_STOP on
-- Supplemental checks absent from tests_v5.sql; all data is rolled back.
BEGIN;
SET ROLE parking_api;
DO $$
DECLARE
  u1 uuid := gen_random_uuid(); u2 uuid := gen_random_uuid();
  vehicle uuid := gen_random_uuid(); event uuid := gen_random_uuid();
  client uuid := gen_random_uuid(); constraint_name text;
BEGIN
  INSERT INTO users(id, kakao_id) VALUES (u1, -70001), (u2, -70002);
  INSERT INTO vehicles(id, user_id, name, bluetooth_address)
    VALUES (vehicle, u1, 'verification', 'AA:BB:CC:DD:EE:FF');
  BEGIN
    INSERT INTO vehicles(id, user_id, name, bluetooth_address)
      VALUES (gen_random_uuid(), u1, 'duplicate', 'AA:BB:CC:DD:EE:00');
    RAISE EXCEPTION 'Active vehicle uniqueness was not enforced';
  EXCEPTION WHEN unique_violation THEN
    GET STACKED DIAGNOSTICS constraint_name = CONSTRAINT_NAME;
    IF constraint_name <> 'uq_vehicles_active_user' THEN RAISE; END IF;
  END;
  INSERT INTO parking_events(id,user_id,vehicle_id,client_event_id,request_hash,
    vehicle_name_snapshot,detected_at,confirmed_at,original_location,confirmed_location,confirmation_method)
    VALUES (event,u1,vehicle,client,'hash','verification',now(),now(),
      'SRID=4326;POINT(127 37)','SRID=4326;POINT(127 37)','CONFIRMED');
  BEGIN
    INSERT INTO parking_events SELECT gen_random_uuid(), user_id, vehicle_id, client_event_id,
      request_hash, vehicle_name_snapshot, detected_at, confirmed_at, last_known_location, last_known_at,
      original_location, accuracy_m, remeasure_count, confirmed_location, confirmation_method, address, created_at
      FROM parking_events WHERE id = event;
    RAISE EXCEPTION 'Client event uniqueness was not enforced';
  EXCEPTION WHEN unique_violation THEN NULL;
  END;
  BEGIN
    UPDATE parking_events SET user_id = u2 WHERE id = event;
    RAISE EXCEPTION 'Composite vehicle ownership FK was not enforced';
  EXCEPTION WHEN foreign_key_violation THEN NULL;
  END;
  BEGIN
    UPDATE parking_events SET original_location = NULL, confirmation_method = 'AUTO' WHERE id = event;
    RAISE EXCEPTION 'AUTO without original GPS was allowed';
  EXCEPTION WHEN check_violation THEN
    GET STACKED DIAGNOSTICS constraint_name = CONSTRAINT_NAME;
    IF constraint_name <> 'ck_pe_no_auto_without_gps' THEN RAISE; END IF;
  END;
  BEGIN
    UPDATE parking_events SET confirmed_location = 'SRID=4326;POINT(128 37)' WHERE id = event;
    RAISE EXCEPTION 'Adjustment beyond 200m was allowed';
  EXCEPTION WHEN check_violation THEN
    GET STACKED DIAGNOSTICS constraint_name = CONSTRAINT_NAME;
    IF constraint_name <> 'ck_pe_adjust_200m' THEN RAISE; END IF;
  END;
  INSERT INTO location_access_logs(user_id,parking_event_id,action,purpose)
    VALUES (u1,event,'USE','isolated verification');
  DELETE FROM parking_events WHERE id = event;
  IF NOT EXISTS (SELECT 1 FROM location_access_logs WHERE user_id = u1 AND parking_event_id IS NULL) THEN
    RAISE EXCEPTION 'Event deletion did not detach location logs';
  END IF;
  DELETE FROM users WHERE id = u1;
  IF EXISTS (SELECT 1 FROM vehicles WHERE user_id = u1)
     OR EXISTS (SELECT 1 FROM location_access_logs WHERE user_id = u1) THEN
    RAISE EXCEPTION 'User deletion did not cascade';
  END IF;
END $$;
ROLLBACK;
\echo PASS: vehicle uniqueness, event idempotency, ownership FK, GPS, 200m, deletion behavior
