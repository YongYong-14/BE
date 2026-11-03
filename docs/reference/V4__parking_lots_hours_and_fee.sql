-- 번호 V4는 프로젝트에서 아직 쓰지 않았을 때만 사용. 이미 있으면 다음 번호로 바꿀 것.
-- V4: parking_lots 운영시간 6칸 + 요금 3값
-- 전제: V1(schema_v5), V2(migration_extra), V3(roles_and_grants) 적용 후.
-- 요금은 기존 is_paid 값을 fee_type으로 옮긴 뒤 컬럼을 지운다. 운영시간 원문(operating_hours)은 구조가 달라 옮기지 않고 지운다.
-- 주차장 적재 전에 적용하는 것을 전제로 한다(적재 후라면 operating_hours를 먼저 백업).

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
COMMENT ON COLUMN parking_lots.holiday_open IS '공휴일 운영 시작. 일요일도 이 칸을 쓴다(공휴일 > 일요일 > 토요일 > 평일 순). 시작·종료는 둘 다 있거나 둘 다 NULL';
