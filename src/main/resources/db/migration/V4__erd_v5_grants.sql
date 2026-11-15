SET LOCAL search_path = public, pg_catalog;

-- ERD v5 역할·권한. migration_extra.sql 다음에 테이블 소유자 계정으로 실행.
-- 원칙: 소유자(마이그레이션) 계정으로 앱·적재 작업을 하지 않는다.
--       소유자·슈퍼유저는 트리거를 끌 수도 있으므로 DB 보호의 대상이 아니라 운영 통제의 대상이다.
--
--   parking_app   : API 서버. 사용자 도메인 읽기·쓰기 + 참조 데이터 읽기 전용
--   parking_etl   : 데이터 적재·예측 버전 동결·활성화. 동결된 데이터는 트리거가 막음
--   parking_admin : 동결 버전 정리(purge)만 추가로 허용
-- 실제 로그인 계정은 아래처럼 만든 뒤 그룹 역할을 부여한다.
--   CREATE ROLE api_server LOGIN PASSWORD '...' IN ROLE parking_app;

-- Group roles are provisioned by scripts/db/bootstrap.sql (administrator).

-- Only touch objects owned by this migration login; PostGIS belongs to admin.
DO $$ DECLARE obj record; BEGIN
  FOR obj IN SELECT n.nspname, c.relname, c.relkind FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relowner = current_user::regrole
      AND c.relkind IN ('r', 'p', 'S')
  LOOP
    EXECUTE format('REVOKE ALL ON %s %I.%I FROM PUBLIC',
      CASE WHEN obj.relkind = 'S' THEN 'SEQUENCE' ELSE 'TABLE' END,
      obj.nspname, obj.relname);
  END LOOP;
END $$;
GRANT USAGE ON SCHEMA public TO parking_app, parking_etl, parking_admin;

-- ─── API 서버 ─────────────────────────────────
GRANT SELECT, INSERT, UPDATE, DELETE ON
  users, refresh_tokens, vehicles, notification_settings,
  parking_events, risk_assessments, assessment_reasons
TO parking_app;
GRANT SELECT, INSERT ON user_agreements, location_access_logs TO parking_app;   -- 이력은 추가만
GRANT SELECT ON
  zone_types, zone_rule_sets, zone_rules, facilities, parking_lots, holidays,
  spatial_grids, prediction_versions, prediction_target_grids, risk_predictions,
  parcel_report_stats, geocoded_parcels, data_imports, source_availability
TO parking_app;
GRANT USAGE ON SEQUENCE user_agreements_id_seq, location_access_logs_id_seq TO parking_app;

-- ─── 적재·모델 운영 ───────────────────────────
GRANT SELECT, INSERT, UPDATE, DELETE ON
  data_imports, source_availability, geocoded_parcels, enforcement_records,
  parcel_report_stats, spatial_grids, report_aggregates,
  prediction_versions, prediction_target_grids, risk_predictions,
  zone_types, zone_rule_sets, zone_rules, facilities, parking_lots, holidays
TO parking_etl;
GRANT SELECT ON risk_assessments TO parking_etl;   -- purge 전 참조 확인용 조회
GRANT USAGE ON SEQUENCE enforcement_records_id_seq, spatial_grids_id_seq TO parking_etl;

-- ─── 정리 관리자 ──────────────────────────────
-- GRANT parking_etl TO parking_admin is applied by administrator bootstrap.

-- ─── 함수 실행 권한 ───────────────────────────
REVOKE ALL ON FUNCTION freeze_prediction_version(uuid)   FROM PUBLIC;
REVOKE ALL ON FUNCTION activate_prediction_version(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION purge_prediction_version(uuid)    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION freeze_prediction_version(uuid), activate_prediction_version(uuid) TO parking_etl;
GRANT EXECUTE ON FUNCTION purge_prediction_version(uuid) TO parking_admin;
