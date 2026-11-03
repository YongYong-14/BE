-- Project V5: V1 verifies PostGIS, V2 schema, V3 guards, V4 grants.
-- Flyway runs this entire migration in one PostgreSQL transaction.
SET LOCAL search_path = public, pg_catalog;
-- Hold the lock before the data check to prevent concurrent writes between check and DROP.
LOCK TABLE public.parking_lots IN ACCESS EXCLUSIVE MODE;

-- 안전장치: 기존 운영시간 원문이 있으면 지우지 않고 중단한다. 먼저 백업하거나 새 컬럼으로 옮길 것.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM parking_lots WHERE operating_hours IS NOT NULL) THEN
    RAISE EXCEPTION 'parking_lots.operating_hours has data; back it up before dropping';
  END IF;
END $$;

ALTER TABLE parking_lots
  ADD COLUMN fee_type      varchar(10),
  ADD COLUMN weekday_open  time,
  ADD COLUMN weekday_close time,
  ADD COLUMN saturday_open  time,
  ADD COLUMN saturday_close time,
  ADD COLUMN holiday_open   time,
  ADD COLUMN holiday_close  time;

UPDATE parking_lots
   SET fee_type = CASE WHEN is_paid IS TRUE THEN 'PAID' WHEN is_paid IS FALSE THEN 'FREE' END;

ALTER TABLE parking_lots
  DROP COLUMN is_paid,
  DROP COLUMN operating_hours;

ALTER TABLE parking_lots
  ADD CONSTRAINT ck_pl_fee_type CHECK (fee_type IS NULL OR fee_type IN ('FREE','PAID','MIXED')),
  ADD CONSTRAINT ck_pl_weekday_pair  CHECK ((weekday_open  IS NULL) = (weekday_close  IS NULL)),
  ADD CONSTRAINT ck_pl_saturday_pair CHECK ((saturday_open IS NULL) = (saturday_close IS NULL)),
  ADD CONSTRAINT ck_pl_holiday_pair  CHECK ((holiday_open  IS NULL) = (holiday_close  IS NULL));

COMMENT ON COLUMN parking_lots.fee_type IS 'FREE / PAID / MIXED(시간대에 따라 유료·무료). NULL = 원천에 없음';
COMMENT ON COLUMN parking_lots.holiday_open IS '공휴일 운영 시작. 일요일 공용은 원천 정의 확인 전 임시 정책. 시작·종료는 둘 다 있거나 둘 다 NULL';
