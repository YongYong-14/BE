SET LOCAL search_path = public, pg_catalog;

-- ERD v5: DBML로 표현할 수 없는 객체. schema_v5.sql 실행 직후, roles_and_grants.sql 이전에 실행.
-- 이 파일은 테이블 소유자(마이그레이션 계정)로 실행한다. 그 계정이 purge 함수의 소유자가 된다.
-- 전제: CREATE EXTENSION postgis;
--
-- 예측 버전 수명주기
--   적재 중(frozen_at NULL) ──freeze──▶ 동결(frozen_at 설정) ──activate──▶ 사용 중(active)
--   · 동결 판단은 "지금 active인가"가 아니라 "한 번 동결됐는가"로 한다.
--   · 동결된 버전은 대상 격자·예측 행·설정값을 INSERT/UPDATE/DELETE할 수 없다.
--   · 고칠 일이 생기면 새 버전을 만든다.
-- 잠금 규칙
--   · 자식(대상 격자·예측 행) 변경: 부모 버전 행을 FOR SHARE로 잠근 뒤 frozen_at 확인
--   · 동결·활성화·설정 변경: 부모 버전 행 UPDATE(FOR NO KEY UPDATE 이상) → FOR SHARE와 충돌
--   → 적재와 동결이 겹치면 한쪽이 기다렸다가 최신 상태를 보고 판단한다.

-- ─── 부분 유니크 ───────────────────────────────
CREATE UNIQUE INDEX uq_vehicles_active_user
  ON vehicles (user_id) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uq_prediction_versions_active
  ON prediction_versions (active) WHERE active;
CREATE UNIQUE INDEX uq_data_imports_active_source
  ON data_imports (source_dataset) WHERE active;

-- ─── 공간 인덱스 ──────────────────────────────
CREATE INDEX ix_facilities_spatial        ON facilities          USING gist (spatial_data);
CREATE INDEX ix_parking_lots_location     ON parking_lots        USING gist (location);
CREATE INDEX ix_geocoded_parcels_location ON geocoded_parcels    USING gist (location);
CREATE INDEX ix_parcel_stats_location     ON parcel_report_stats USING gist (location);
CREATE INDEX ix_enforcement_location      ON enforcement_records USING gist (location);
CREATE INDEX ix_spatial_grids_boundary    ON spatial_grids       USING gist (boundary);

-- ═════════════════════════════════════════════
-- 예측 버전
-- ═════════════════════════════════════════════

-- 1) 자식 행 변경 차단 (INSERT 포함, OLD·NEW 양쪽 버전 확인)
CREATE OR REPLACE FUNCTION guard_prediction_child()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v uuid; f timestamptz;
BEGIN
  FOREACH v IN ARRAY ARRAY[
      CASE WHEN TG_OP <> 'INSERT' THEN OLD.version_id END,
      CASE WHEN TG_OP <> 'DELETE' THEN NEW.version_id END] LOOP
    CONTINUE WHEN v IS NULL;
    SELECT frozen_at INTO f FROM prediction_versions WHERE id = v FOR SHARE;
    -- 부모 버전이 이미 삭제된 상태 = 부모 삭제(purge)에 따른 CASCADE → 허용.
    -- 부모 삭제 자체는 guard_prediction_version이 역할로 통제하므로 별도 우회 경로가 없다.
    IF NOT FOUND THEN CONTINUE; END IF;
    IF f IS NOT NULL THEN
      RAISE EXCEPTION 'FROZEN_VERSION: % on frozen prediction version % is not allowed (create a new version)', TG_OP, v;
    END IF;
  END LOOP;
  RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END $$;

CREATE TRIGGER trg_rp_guard  BEFORE INSERT OR UPDATE OR DELETE ON risk_predictions
  FOR EACH ROW EXECUTE FUNCTION guard_prediction_child();
CREATE TRIGGER trg_ptg_guard BEFORE INSERT OR UPDATE OR DELETE ON prediction_target_grids
  FOR EACH ROW EXECUTE FUNCTION guard_prediction_child();

-- 2) 버전 행 자체: 동결 전환 시 실제 행 대조 / 동결 후에는 active 외 변경 금지
CREATE OR REPLACE FUNCTION guard_prediction_version()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v_targets bigint; v_rows bigint; v_incomplete bigint; x prediction_versions;
BEGIN
  IF TG_OP = 'DELETE' THEN
    -- 동결 버전 삭제는 purge 함수(SECURITY DEFINER) 안에서만: 실행 역할이 함수 소유자여야 하고
    -- 함수가 같은 트랜잭션에서 지정한 대상과 일치해야 한다. 설정값만으로는 통과 불가.
    IF OLD.frozen_at IS NOT NULL AND NOT (
         current_user = (SELECT proowner::regrole::text FROM pg_proc
                         WHERE oid = 'purge_prediction_version(uuid)'::regprocedure)
         AND current_setting('app.purge_version', true) = OLD.id::text) THEN
      RAISE EXCEPTION 'FROZEN_VERSION_DELETE: frozen version % can only be removed via purge_prediction_version()', OLD.id;
    END IF;
    RETURN OLD;
  END IF;

  IF TG_OP = 'UPDATE' AND OLD.frozen_at IS NOT NULL THEN
    x := NEW; x.active := OLD.active;
    IF x IS DISTINCT FROM OLD THEN
      RAISE EXCEPTION 'FROZEN_VERSION_SETTINGS: frozen version %: only "active" may change', OLD.id;
    END IF;
    RETURN NEW;
  END IF;

  -- 적재 중 → 동결 전환(INSERT 시 바로 동결 포함): 실제 데이터로 검증
  IF NEW.frozen_at IS NOT NULL THEN
    SELECT count(*) INTO v_targets FROM prediction_target_grids WHERE version_id = NEW.id;
    SELECT count(*) INTO v_rows    FROM risk_predictions        WHERE version_id = NEW.id;
    SELECT count(*) INTO v_incomplete FROM (
      SELECT t.grid_id FROM prediction_target_grids t
      LEFT JOIN risk_predictions r ON r.version_id = t.version_id AND r.grid_id = t.grid_id
      WHERE t.version_id = NEW.id GROUP BY t.grid_id
      HAVING count(DISTINCT (r.weekday, r.time_slot)) <> 56) q;
    IF v_targets = 0 OR v_targets <> NEW.target_cell_count
       OR v_incomplete > 0 OR v_rows <> v_targets * 56 THEN
      RAISE EXCEPTION 'FREEZE_REJECTED: targets %, declared %, rows %, incomplete grids %',
        v_targets, NEW.target_cell_count, v_rows, v_incomplete;
    END IF;
    NEW.loaded_row_count := v_rows;
    NEW.validated_at := COALESCE(NEW.validated_at, now());
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER trg_pv_guard BEFORE INSERT OR UPDATE OR DELETE ON prediction_versions
  FOR EACH ROW EXECUTE FUNCTION guard_prediction_version();

-- 3) 운영 함수
CREATE OR REPLACE FUNCTION freeze_prediction_version(p_version uuid)
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  PERFORM 1 FROM prediction_versions WHERE id = p_version FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'version % not found', p_version; END IF;
  UPDATE prediction_versions SET frozen_at = now()
   WHERE id = p_version AND frozen_at IS NULL;     -- 검증은 trg_pv_guard가 수행
END $$;

CREATE OR REPLACE FUNCTION activate_prediction_version(p_version uuid)
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  PERFORM 1 FROM prediction_versions WHERE id = p_version AND frozen_at IS NOT NULL FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'version % not found or not frozen', p_version; END IF;
  UPDATE prediction_versions SET active = false WHERE active AND id <> p_version;
  UPDATE prediction_versions SET active = true  WHERE id = p_version;
END $$;

CREATE OR REPLACE FUNCTION purge_prediction_version(p_version uuid)
RETURNS void LANGUAGE plpgsql
SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
  PERFORM 1 FROM prediction_versions WHERE id = p_version FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'version % not found', p_version; END IF;
  IF EXISTS (SELECT 1 FROM prediction_versions WHERE id = p_version AND active) THEN
    RAISE EXCEPTION 'PURGE_ACTIVE: cannot purge active version %', p_version;
  END IF;
  IF EXISTS (SELECT 1 FROM risk_assessments WHERE prediction_version_id = p_version) THEN
    RAISE EXCEPTION 'PURGE_REFERENCED: version % is referenced by assessments', p_version;
  END IF;
  PERFORM set_config('app.purge_version', p_version::text, true);
  DELETE FROM prediction_versions WHERE id = p_version;   -- 자식은 CASCADE (부모 부재 → 자식 가드 통과)
  PERFORM set_config('app.purge_version', '', true);
END $$;
REVOKE ALL ON FUNCTION purge_prediction_version(uuid) FROM PUBLIC;

-- ═════════════════════════════════════════════
-- 규칙 묶음
-- ═════════════════════════════════════════════

CREATE OR REPLACE FUNCTION guard_zone_rule()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE rs text; p timestamptz;
BEGIN
  FOREACH rs IN ARRAY ARRAY[
      CASE WHEN TG_OP <> 'INSERT' THEN OLD.rule_set END,
      CASE WHEN TG_OP <> 'DELETE' THEN NEW.rule_set END] LOOP
    CONTINUE WHEN rs IS NULL;
    SELECT published_at INTO p FROM zone_rule_sets WHERE rule_set = rs FOR SHARE;
    IF p IS NOT NULL THEN
      RAISE EXCEPTION 'PUBLISHED_RULE_SET: % on published rule_set % is not allowed (create a new rule_set)', TG_OP, rs;
    END IF;
  END LOOP;
  RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END $$;

CREATE TRIGGER trg_zone_rules_guard BEFORE INSERT OR UPDATE OR DELETE ON zone_rules
  FOR EACH ROW EXECUTE FUNCTION guard_zone_rule();

CREATE OR REPLACE FUNCTION guard_zone_rule_set()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' AND OLD.published_at IS NOT NULL THEN
    RAISE EXCEPTION 'published rule_set % cannot be deleted', OLD.rule_set;
  END IF;
  IF TG_OP = 'UPDATE' AND OLD.published_at IS NOT NULL
     AND (NEW.rule_set, NEW.published_at) IS DISTINCT FROM (OLD.rule_set, OLD.published_at) THEN
    RAISE EXCEPTION 'published rule_set % is immutable', OLD.rule_set;
  END IF;
  RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END $$;

CREATE TRIGGER trg_zone_rule_sets_guard BEFORE UPDATE OR DELETE ON zone_rule_sets
  FOR EACH ROW EXECUTE FUNCTION guard_zone_rule_set();

-- ═════════════════════════════════════════════
-- 판정은 공개된 규칙·동결된 예측만 참조
-- ═════════════════════════════════════════════

CREATE OR REPLACE FUNCTION guard_assessment_refs()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.zone_rule_set IS NOT NULL AND NOT EXISTS (
       SELECT 1 FROM zone_rule_sets WHERE rule_set = NEW.zone_rule_set AND published_at IS NOT NULL) THEN
    RAISE EXCEPTION 'RULE_SET_NOT_PUBLISHED: rule_set % is not published', NEW.zone_rule_set;
  END IF;
  IF NEW.prediction_version_id IS NOT NULL AND NOT EXISTS (
       SELECT 1 FROM prediction_versions WHERE id = NEW.prediction_version_id AND frozen_at IS NOT NULL) THEN
    RAISE EXCEPTION 'VERSION_NOT_FROZEN: prediction version % is not frozen', NEW.prediction_version_id;
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER trg_ra_refs BEFORE INSERT OR UPDATE ON risk_assessments
  FOR EACH ROW EXECUTE FUNCTION guard_assessment_refs();

-- ═════════════════════════════════════════════
-- 성공 판정의 대표 근거 필수 (커밋 시점 검사: 판정·근거를 한 트랜잭션에서 넣을 수 있게)
-- ═════════════════════════════════════════════
CREATE OR REPLACE FUNCTION check_assessment_reason(p_assessment uuid)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE a risk_assessments; r text;
BEGIN
  SELECT * INTO a FROM risk_assessments WHERE id = p_assessment;
  IF NOT FOUND OR a.status <> 'SUCCEEDED' OR a.level NOT IN (1,2) THEN RETURN; END IF;
  SELECT reason_type INTO r FROM assessment_reasons WHERE assessment_id = p_assessment AND rank = 1;
  IF r IS NULL THEN
    RAISE EXCEPTION 'REASON_REQUIRED: succeeded level % assessment % has no rank-1 reason', a.level, p_assessment;
  END IF;
  IF a.level = 1 AND r <> 'FACILITY_RULE' THEN
    RAISE EXCEPTION 'REASON_MISMATCH: level 1 requires FACILITY_RULE, got %', r;
  END IF;
  IF a.level = 2 AND r NOT IN ('MODEL','REPORT_HISTORY') THEN
    RAISE EXCEPTION 'REASON_MISMATCH: level 2 requires MODEL or REPORT_HISTORY, got %', r;
  END IF;
END $$;

CREATE OR REPLACE FUNCTION trg_check_reason_from_assessment()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN PERFORM check_assessment_reason(NEW.id); RETURN NULL; END $$;

CREATE OR REPLACE FUNCTION trg_check_reason_from_reason()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP <> 'INSERT' THEN PERFORM check_assessment_reason(OLD.assessment_id); END IF;
  IF TG_OP <> 'DELETE' THEN PERFORM check_assessment_reason(NEW.assessment_id); END IF;
  RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER ctr_ra_reason_required
  AFTER INSERT OR UPDATE ON risk_assessments
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
  EXECUTE FUNCTION trg_check_reason_from_assessment();

CREATE CONSTRAINT TRIGGER ctr_ar_reason_required
  AFTER INSERT OR UPDATE OR DELETE ON assessment_reasons
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
  EXECUTE FUNCTION trg_check_reason_from_reason();
