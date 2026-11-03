-- ERD v5 회귀 테스트
-- 실행 순서: 빈 DB → CREATE EXTENSION postgis → schema_v5.sql → migration_extra.sql → roles_and_grants.sql → 이 파일
-- 슈퍼유저로 실행(테스트용 로그인 역할 생성·SET ROLE). 각 케이스는 "기대한 오류 메시지 접두사"(FROZEN_VERSION: 등. SQLSTATE 아님)가 났는지까지 확인한다.
-- 테스트 전용 스키마 t를 만들고 끝나면 지운다. 마지막 줄에 PASS/FAIL 합계가 나오고, FAIL이 하나라도 있으면 에러로 끝난다.

\set ON_ERROR_STOP 1
SET client_min_messages = notice;

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='t_app')   THEN CREATE ROLE t_app   LOGIN IN ROLE parking_app;   END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='t_etl')   THEN CREATE ROLE t_etl   LOGIN IN ROLE parking_etl;   END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='t_admin') THEN CREATE ROLE t_admin LOGIN IN ROLE parking_admin; END IF;
END $$;

DROP SCHEMA IF EXISTS t CASCADE;
CREATE SCHEMA t;
GRANT USAGE ON SCHEMA t TO PUBLIC;
CREATE TABLE t.results(label text, ok boolean, detail text);
GRANT ALL ON t.results TO PUBLIC;

-- 기대 에러: SQLERRM이 pattern(LIKE)과 맞아야 PASS. 다른 에러거나 에러가 없으면 FAIL.
-- 문장들은 서브트랜잭션에서 실행되고 끝에 SET CONSTRAINTS ALL IMMEDIATE로 지연 검사까지 강제한 뒤 롤백된다.
CREATE OR REPLACE FUNCTION t.expect_error(p_label text, p_pattern text, VARIADIC p_sql text[])
RETURNS void LANGUAGE plpgsql AS $$
DECLARE s text;
BEGIN
  BEGIN
    FOREACH s IN ARRAY p_sql LOOP EXECUTE s; END LOOP;
    SET CONSTRAINTS ALL IMMEDIATE;
    RAISE EXCEPTION 'NO_ERROR_SENTINEL';
  EXCEPTION WHEN OTHERS THEN
    INSERT INTO t.results VALUES (p_label,
      SQLERRM LIKE p_pattern AND SQLERRM <> 'NO_ERROR_SENTINEL',
      CASE WHEN SQLERRM = 'NO_ERROR_SENTINEL' THEN 'no error raised' ELSE SQLERRM END);
  END;
END $$;

-- 기대 성공: 에러 없이 끝나면 PASS (지연 검사 포함). 변경은 유지된다.
CREATE OR REPLACE FUNCTION t.expect_ok(p_label text, VARIADIC p_sql text[])
RETURNS void LANGUAGE plpgsql AS $$
DECLARE s text;
BEGIN
  BEGIN
    FOREACH s IN ARRAY p_sql LOOP EXECUTE s; END LOOP;
    SET CONSTRAINTS ALL IMMEDIATE;
    INSERT INTO t.results VALUES (p_label, true, 'ok');
  EXCEPTION WHEN OTHERS THEN
    INSERT INTO t.results VALUES (p_label, false, SQLERRM);
  END;
END $$;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA t TO PUBLIC;

-- ─── 공통 데이터 (소유자로 준비) ──────────────
INSERT INTO data_imports(id,source_dataset,file_name,file_hash) VALUES ('00000000-0000-0000-0000-00000000d001','yongin','f.csv','x');
INSERT INTO spatial_grids(id,grid_x,grid_y,boundary)
  SELECT g,g,g,'SRID=4326;POLYGON((127 37,127.001 37,127.001 37.001,127 37))' FROM generate_series(1,3) g;
INSERT INTO prediction_versions(id,model_type,model_version,training_start,training_end,target_definition,source_import_id,observed_end,smoothing_radius_m,percentile_basis,target_cell_count)
SELECT ('00000000-0000-0000-0000-0000000000f'||n)::uuid, 'BASELINE_MEAN', 'v'||n, '2025-01-01','2025-07-31','ROW_COUNT',
       '00000000-0000-0000-0000-00000000d001','2025-09-25',50,'SAME_SLOT',1 FROM generate_series(1,3) n;
INSERT INTO prediction_target_grids SELECT ('00000000-0000-0000-0000-0000000000f'||n)::uuid, 1 FROM generate_series(1,3) n;
INSERT INTO risk_predictions
  SELECT ('00000000-0000-0000-0000-0000000000f'||n)::uuid,1,w,s,0.1,0.1,50
  FROM generate_series(1,3) n, generate_series(1,7) w, generate_series(0,7) s;
-- v1: 동결+활성 / v2: 동결(비활성, 판정 미참조) / v3: 적재 중
SELECT freeze_prediction_version('00000000-0000-0000-0000-0000000000f1');
SELECT freeze_prediction_version('00000000-0000-0000-0000-0000000000f2');
SELECT activate_prediction_version('00000000-0000-0000-0000-0000000000f1');

INSERT INTO zone_types VALUES ('CROSSWALK','횡단보도',true),('HYDRANT','소화전',true);
INSERT INTO zone_rule_sets(rule_set) VALUES ('2026-11'),('draft');
INSERT INTO zone_rules(id,rule_set,zone_type,geometry_rule,legal_distance_m) VALUES (gen_random_uuid(),'2026-11','CROSSWALK','DISTANCE',10);
UPDATE zone_rule_sets SET published_at = now() WHERE rule_set = '2026-11';

INSERT INTO users(id,kakao_id) VALUES ('00000000-0000-0000-0000-000000000001',1);
INSERT INTO vehicles(id,user_id,name,bluetooth_address) VALUES ('00000000-0000-0000-0000-0000000000a1','00000000-0000-0000-0000-000000000001','a','AA:BB:CC:DD:EE:FF');
INSERT INTO parking_events(id,user_id,vehicle_id,client_event_id,request_hash,vehicle_name_snapshot,detected_at,confirmed_at,original_location,confirmed_location,confirmation_method)
SELECT ('00000000-0000-0000-0000-0000000000e'||n)::uuid,'00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-0000000000a1',
       gen_random_uuid(),'h','a',now(),now(),'SRID=4326;POINT(127.09 37.32)','SRID=4326;POINT(127.0905 37.3201)','ADJUSTED'
FROM generate_series(1,6) n;

-- ═════════ 1. purge 우회 (GPT 재현 경로) ═════════
SET ROLE t_etl;
SELECT t.expect_error('P1 etl: GUC 위조 후 활성 버전 예측 DELETE', 'FROZEN_VERSION:%',
  $q$SELECT set_config('app.purge_version','00000000-0000-0000-0000-0000000000f1',true)$q$,
  $q$DELETE FROM risk_predictions WHERE version_id='00000000-0000-0000-0000-0000000000f1' AND weekday=1 AND time_slot=0$q$);
SELECT t.expect_error('P2 etl: GUC 위조 후 동결 버전 행 DELETE', 'FROZEN_VERSION_DELETE:%',
  $q$SELECT set_config('app.purge_version','00000000-0000-0000-0000-0000000000f2',true)$q$,
  $q$DELETE FROM prediction_versions WHERE id='00000000-0000-0000-0000-0000000000f2'$q$);
SELECT t.expect_error('P3 etl: purge 함수 실행 권한 없음', 'permission denied for function purge_prediction_version',
  $q$SELECT purge_prediction_version('00000000-0000-0000-0000-0000000000f2')$q$);
RESET ROLE;

SET ROLE t_app;
SELECT t.expect_error('P4 app: 예측 테이블 DELETE 권한 없음', 'permission denied for table risk_predictions',
  $q$DELETE FROM risk_predictions WHERE version_id='00000000-0000-0000-0000-0000000000f1'$q$);
SELECT t.expect_error('P5 app: 예측 버전 UPDATE 권한 없음', 'permission denied for table prediction_versions',
  $q$UPDATE prediction_versions SET active=false$q$);
SELECT t.expect_error('P6 app: purge 실행 권한 없음', 'permission denied for function purge_prediction_version',
  $q$SELECT purge_prediction_version('00000000-0000-0000-0000-0000000000f2')$q$);
RESET ROLE;

SET ROLE t_admin;
SELECT t.expect_error('P7 admin: 활성 버전 purge 거부', 'PURGE_ACTIVE:%',
  $q$SELECT purge_prediction_version('00000000-0000-0000-0000-0000000000f1')$q$);
SELECT t.expect_error('P8 admin: purge 권한 있어도 직접 DELETE는 거부', 'FROZEN_VERSION:%',
  $q$SELECT set_config('app.purge_version','00000000-0000-0000-0000-0000000000f1',true)$q$,
  $q$DELETE FROM risk_predictions WHERE version_id='00000000-0000-0000-0000-0000000000f1'$q$);
SELECT t.expect_ok('P9 admin: 비활성·미참조 v2 purge 성공',
  $q$SELECT purge_prediction_version('00000000-0000-0000-0000-0000000000f2')$q$);
RESET ROLE;
INSERT INTO t.results SELECT 'P10 purge 후 v2 예측 행 0개', count(*) = 0, count(*)::text
  FROM risk_predictions WHERE version_id='00000000-0000-0000-0000-0000000000f2';
INSERT INTO t.results SELECT 'P11 활성 v1 예측은 56행 그대로', count(*) = 56, count(*)::text
  FROM risk_predictions WHERE version_id='00000000-0000-0000-0000-0000000000f1';

-- ═════════ 2. 동결 보호 (에러 코드로 원인 확인) ═════════
SET ROLE t_etl;
SELECT t.expect_error('F1 동결 버전에 대상 격자 INSERT (PK 충돌 없는 새 격자)', 'FROZEN_VERSION:%',
  $q$INSERT INTO prediction_target_grids VALUES ('00000000-0000-0000-0000-0000000000f1',2)$q$);
SELECT t.expect_error('F2 동결 버전에 예측 INSERT (트리거가 PK보다 먼저)', 'FROZEN_VERSION:%',
  $q$INSERT INTO risk_predictions VALUES ('00000000-0000-0000-0000-0000000000f1',1,1,0,9,9,1)$q$);
SELECT t.expect_error('F3 적재 중 v3 행을 동결 v1으로 이동 (NEW 쪽 검사)', 'FROZEN_VERSION:%',
  $q$UPDATE risk_predictions SET version_id='00000000-0000-0000-0000-0000000000f1' WHERE version_id='00000000-0000-0000-0000-0000000000f3' AND weekday=1 AND time_slot=0$q$);
SELECT t.expect_error('F4 동결 버전 설정값 변경', 'FROZEN_VERSION_SETTINGS:%',
  $q$UPDATE prediction_versions SET caution_top_percent=5 WHERE id='00000000-0000-0000-0000-0000000000f1'$q$);
SELECT t.expect_error('F5 미동결 버전 직접 활성화', '%ck_pv_active_requires_frozen%',
  $q$UPDATE prediction_versions SET active=true WHERE id='00000000-0000-0000-0000-0000000000f3'$q$);
SELECT t.expect_error('F6 행 빠진 상태로 직접 동결', 'FREEZE_REJECTED:%',
  $q$DELETE FROM risk_predictions WHERE version_id='00000000-0000-0000-0000-0000000000f3' AND weekday=7 AND time_slot=7$q$,
  $q$UPDATE prediction_versions SET frozen_at=now() WHERE id='00000000-0000-0000-0000-0000000000f3'$q$);
SELECT t.expect_ok('F7 적재 중 v3는 자유롭게 수정 가능',
  $q$UPDATE risk_predictions SET smoothed_count=0.2 WHERE version_id='00000000-0000-0000-0000-0000000000f3' AND weekday=1 AND time_slot=0$q$);
-- 규칙
SELECT t.expect_error('R1 공개된 규칙 묶음에 규칙 추가', 'PUBLISHED_RULE_SET:%',
  $q$INSERT INTO zone_rules(id,rule_set,zone_type,geometry_rule,legal_distance_m) VALUES (gen_random_uuid(),'2026-11','HYDRANT','DISTANCE',5)$q$);
SELECT t.expect_error('R2 공개된 규칙 수정', 'PUBLISHED_RULE_SET:%',
  $q$UPDATE zone_rules SET legal_distance_m=5 WHERE rule_set='2026-11'$q$);
SELECT t.expect_ok('R3 작성 중 묶음에는 추가 가능',
  $q$INSERT INTO zone_rules(id,rule_set,zone_type,geometry_rule,legal_distance_m) VALUES (gen_random_uuid(),'draft','HYDRANT','DISTANCE',5)$q$);
RESET ROLE;

-- ═════════ 3. 성공 판정의 필수 근거 ═════════
SET ROLE t_app;
SELECT t.expect_ok('A1 2단계 성공: 공개 규칙+동결 모델+MODEL 근거',
  $q$INSERT INTO risk_assessments(id,parking_event_id,status,level,prediction_status,zone_rule_set,prediction_version_id)
     VALUES ('00000000-0000-0000-0000-00000000aa01','00000000-0000-0000-0000-0000000000e1','SUCCEEDED',2,'OK','2026-11','00000000-0000-0000-0000-0000000000f1')$q$,
  $q$INSERT INTO assessment_reasons(id,assessment_id,rank,reason_type,description)
     VALUES (gen_random_uuid(),'00000000-0000-0000-0000-00000000aa01',1,'MODEL','신고 상위 8%')$q$);
SELECT t.expect_error('A2 GPT 재현: 성공 판정의 모델·규칙 참조를 NULL로', '%ck_ra_%_ref_required%',
  $q$UPDATE risk_assessments SET prediction_version_id=NULL, zone_rule_set=NULL WHERE id='00000000-0000-0000-0000-00000000aa01'$q$);
SELECT t.expect_error('A3 성공+OK인데 모델 참조만 NULL', '%ck_ra_model_ref_required%',
  $q$UPDATE risk_assessments SET prediction_version_id=NULL WHERE id='00000000-0000-0000-0000-00000000aa01'$q$);
SELECT t.expect_error('A4 성공인데 규칙 참조만 NULL', '%ck_ra_rule_ref_required%',
  $q$UPDATE risk_assessments SET zone_rule_set=NULL WHERE id='00000000-0000-0000-0000-00000000aa01'$q$);
SELECT t.expect_error('A5 작성 중 규칙 묶음 참조', 'RULE_SET_NOT_PUBLISHED:%',
  $q$UPDATE risk_assessments SET zone_rule_set='draft' WHERE id='00000000-0000-0000-0000-00000000aa01'$q$);
SELECT t.expect_error('A6 미동결 모델 참조', 'VERSION_NOT_FROZEN:%',
  $q$UPDATE risk_assessments SET prediction_version_id='00000000-0000-0000-0000-0000000000f3' WHERE id='00000000-0000-0000-0000-00000000aa01'$q$);
SELECT t.expect_error('A7 1단계 성공인데 근거 행 없음', 'REASON_REQUIRED:%',
  $q$INSERT INTO risk_assessments(id,parking_event_id,status,level,prediction_status,zone_rule_set)
     VALUES (gen_random_uuid(),'00000000-0000-0000-0000-0000000000e2','SUCCEEDED',1,'SKIPPED','2026-11')$q$);
SELECT t.expect_error('A8 1단계인데 근거가 MODEL', 'REASON_MISMATCH:%',
  $q$INSERT INTO risk_assessments(id,parking_event_id,status,level,prediction_status,zone_rule_set)
     VALUES ('00000000-0000-0000-0000-00000000aa03','00000000-0000-0000-0000-0000000000e3','SUCCEEDED',1,'SKIPPED','2026-11')$q$,
  $q$INSERT INTO assessment_reasons(id,assessment_id,rank,reason_type,description) VALUES (gen_random_uuid(),'00000000-0000-0000-0000-00000000aa03',1,'MODEL','x')$q$);
SELECT t.expect_ok('A9 1단계 성공: FACILITY_RULE 근거',
  $q$INSERT INTO risk_assessments(id,parking_event_id,status,level,prediction_status,zone_rule_set)
     VALUES ('00000000-0000-0000-0000-00000000aa04','00000000-0000-0000-0000-0000000000e4','SUCCEEDED',1,'SKIPPED','2026-11')$q$,
  $q$INSERT INTO assessment_reasons(id,assessment_id,rank,reason_type,zone_type,distance_m,threshold_m,description)
     VALUES (gen_random_uuid(),'00000000-0000-0000-0000-00000000aa04',1,'FACILITY_RULE','CROSSWALK',7,10,'횡단보도 7m')$q$);
SELECT t.expect_error('A10 저장된 성공 판정의 근거만 삭제', 'REASON_REQUIRED:%',
  $q$DELETE FROM assessment_reasons WHERE assessment_id='00000000-0000-0000-0000-00000000aa04'$q$);
SELECT t.expect_error('A11 2단계인데 SKIPPED', '%ck_ra_level1_iff_skipped%',
  $q$INSERT INTO risk_assessments(id,parking_event_id,status,level,prediction_status,zone_rule_set)
     VALUES (gen_random_uuid(),'00000000-0000-0000-0000-0000000000e5','SUCCEEDED',2,'SKIPPED','2026-11')$q$);
SELECT t.expect_ok('A12 PARTIAL: 규칙 참조만 있으면 됨',
  $q$INSERT INTO risk_assessments(id,parking_event_id,status,prediction_status,zone_rule_set)
     VALUES (gen_random_uuid(),'00000000-0000-0000-0000-0000000000e5','PARTIAL','OUT_OF_AREA','2026-11')$q$);
SELECT t.expect_ok('A13 FAILED: 참조 없어도 됨',
  $q$INSERT INTO risk_assessments(id,parking_event_id,status,prediction_status,error_code)
     VALUES (gen_random_uuid(),'00000000-0000-0000-0000-0000000000e6','FAILED','MISSING_ROW','PREDICTION_ROW_MISSING')$q$);
RESET ROLE;

-- ═════════ 4. 판정 참조 중인 버전 purge 불가 ═════════
SET ROLE t_admin;
SELECT t.expect_ok('Q0 v3 완성 후 동결',
  $q$INSERT INTO risk_predictions VALUES ('00000000-0000-0000-0000-0000000000f3',1,7,7,0.1,0.1,50)
     ON CONFLICT DO NOTHING$q$,
  $q$SELECT freeze_prediction_version('00000000-0000-0000-0000-0000000000f3')$q$,
  $q$SELECT activate_prediction_version('00000000-0000-0000-0000-0000000000f3')$q$);
SELECT t.expect_error('Q1 판정이 참조하는 비활성 v1 purge 거부', 'PURGE_REFERENCED:%',
  $q$SELECT purge_prediction_version('00000000-0000-0000-0000-0000000000f1')$q$);
RESET ROLE;

-- ═════════ 결과 ═════════
SELECT label, CASE WHEN ok THEN 'PASS' ELSE 'FAIL' END AS result, detail FROM t.results ORDER BY label;
DO $$ DECLARE f int; p int; BEGIN
  SELECT count(*) FILTER (WHERE ok), count(*) FILTER (WHERE NOT ok) INTO p, f FROM t.results;
  RAISE NOTICE 'TOTAL: % PASS, % FAIL', p, f;
  IF f > 0 THEN RAISE EXCEPTION '% test(s) failed', f; END IF;
END $$;
DROP SCHEMA t CASCADE;
