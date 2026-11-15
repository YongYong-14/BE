#!/usr/bin/env bash
# ERD v5 동시 실행 회귀 테스트: 적재(자식 행 변경)와 동결이 겹칠 때 직렬화되는지 확인
#
# 실행 계정: 테스트 DB의 소유자(마이그레이션 계정) 또는 슈퍼유저.
#   - 게이트 테이블 생성, 테스트 데이터 정리(purge_prediction_version: 관리자 전용)가 필요하다.
#   - 운영 DB에서 실행하지 말 것.
# 사용법: PSQL="psql -h HOST -p PORT -U OWNER -d TESTDB" ./tests_concurrency.sh
#
# 순서 보장 방식 (sleep으로 순서를 추정하지 않음)
#   1) holder 세션: 작업 수행 후 게이트가 열릴 때까지 트랜잭션을 열어 둔 채 대기
#   2) pg_stat_activity로 holder가 게이트 대기에 들어간 것을 확인
#   3) contender 세션 시작 → pg_stat_activity로 contender가 'Lock' 대기 중임을 확인
#   4) 게이트 열기 → holder 커밋 → contender가 최신 상태로 판단
set -u
PSQL=${PSQL:-psql}
Q="$PSQL -q -X"
fail=0
RUN=$$

die() { echo "ABORT: $*"; cleanup; exit 2; }

wait_until() {  # $1 label, $2 sql returning 't', timeout 20s
  for _ in $(seq 1 200); do
    [ "$($Q -tA -c "$2" 2>/dev/null)" = "t" ] && return 0
    sleep 0.1
  done
  die "timeout waiting for: $1"
}

check() {  # $1 label, $2 sql returning 't' when expected
  r=$($Q -tA -c "$2")
  if [ "$r" = "t" ]; then echo "PASS  $1"; else echo "FAIL  $1 (got: $r)"; fail=1; fi
}

V1=00000000-0000-0000-0000-0000000cc0f1
V2=00000000-0000-0000-0000-0000000cc0f2
IMP=00000000-0000-0000-0000-0000000cc001

cleanup() {
  $Q -v ON_ERROR_STOP=1 >/dev/null <<SQL || { echo "WARN: cleanup failed — 테스트 데이터가 남았을 수 있음"; fail=1; }
DO \$\$ BEGIN
  IF EXISTS (SELECT 1 FROM prediction_versions WHERE id='$V2' AND frozen_at IS NOT NULL) THEN
    PERFORM purge_prediction_version('$V2');
  END IF;
END \$\$;
DELETE FROM prediction_versions WHERE id IN ('$V1','$V2') AND frozen_at IS NULL;
DELETE FROM spatial_grids WHERE id=900001;
DELETE FROM data_imports WHERE id='$IMP';
DROP TABLE IF EXISTS cc_gate;
SQL
}

setup_version() {  # $1 uuid, $2 model_version
  $Q -v ON_ERROR_STOP=1 >/dev/null <<SQL || die "setup $2"
INSERT INTO prediction_versions(id,model_type,model_version,training_start,training_end,target_definition,
  source_import_id,observed_end,smoothing_radius_m,percentile_basis,target_cell_count)
  VALUES ('$1','LGBM_POISSON','$2','2025-01-01','2025-07-31','ROW_COUNT','$IMP','2025-09-25',50,'SAME_SLOT',1);
INSERT INTO prediction_target_grids VALUES ('$1',900001);
INSERT INTO risk_predictions SELECT '$1',900001,w,s,0,0,100 FROM generate_series(1,7) w, generate_series(0,7) s;
SQL
}

GATE_WAIT="DO \$\$ BEGIN WHILE NOT EXISTS (SELECT 1 FROM cc_gate WHERE k='open') LOOP PERFORM pg_sleep(0.05); END LOOP; END \$\$;"
holder_in_gate() { echo "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE application_name='cc_holder_$RUN' AND query LIKE '%cc_gate%' AND state='active')"; }
contender_blocked() { echo "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE application_name='cc_contender_$RUN' AND wait_event_type='Lock')"; }

# ─── 준비 ───
cleanup >/dev/null 2>&1; fail=0
$Q -v ON_ERROR_STOP=1 >/dev/null <<SQL || die "setup base"
CREATE TABLE cc_gate(k text);
INSERT INTO data_imports(id,source_dataset,file_name,file_hash) VALUES ('$IMP','cc','cc.csv','cc');
INSERT INTO spatial_grids(id,grid_x,grid_y,boundary)
  VALUES (900001,900001,900001,'SRID=4326;POLYGON((127 37,127.001 37,127.001 37.001,127 37))');
SQL
setup_version $V1 cc1
setup_version $V2 cc2

# ─── C1: 적재가 먼저 잠금 → 동결이 기다린 뒤 최신 행 수(55)로 거부 ───
echo "C1: 적재 DELETE(미커밋) 중 동결 시도"
$Q -c "TRUNCATE cc_gate" >/dev/null
PGAPPNAME=cc_holder_$RUN $Q -c "BEGIN; DELETE FROM risk_predictions WHERE version_id='$V1' AND weekday=7 AND time_slot=7; $GATE_WAIT COMMIT;" >/dev/null 2>&1 &
H=$!
wait_until "C1 holder in gate" "$(holder_in_gate)"
PGAPPNAME=cc_contender_$RUN $Q -c "SELECT freeze_prediction_version('$V1');" >/tmp/cc_c1_$RUN.out 2>&1 &
C=$!
wait_until "C1 contender blocked on lock" "$(contender_blocked)"
echo "      확인: 동결 세션이 Lock 대기 중"
$Q -c "INSERT INTO cc_gate VALUES ('open')" >/dev/null
wait $H; wait $C
echo "      freeze 결과: $(grep -o 'FREEZE_REJECTED[^,]*, [^,]*, rows [0-9]*' /tmp/cc_c1_$RUN.out || head -1 /tmp/cc_c1_$RUN.out)"
check "C1 동결 거부, 미동결 상태, 실제 55행" \
  "SELECT frozen_at IS NULL AND (SELECT count(*) FROM risk_predictions WHERE version_id='$V1')=55
     FROM prediction_versions WHERE id='$V1'"
grep -q 'FREEZE_REJECTED' /tmp/cc_c1_$RUN.out || { echo "FAIL  C1 기대 오류 FREEZE_REJECTED 아님"; fail=1; }

# ─── C2: 동결이 먼저 잠금 → 적재가 기다린 뒤 동결을 보고 거부 ───
echo "C2: 동결(미커밋) 중 적재 DELETE 시도"
$Q -c "TRUNCATE cc_gate" >/dev/null
PGAPPNAME=cc_holder_$RUN $Q -c "BEGIN; SELECT freeze_prediction_version('$V2'); $GATE_WAIT COMMIT;" >/dev/null 2>&1 &
H=$!
wait_until "C2 holder in gate" "$(holder_in_gate)"
PGAPPNAME=cc_contender_$RUN $Q -c "DELETE FROM risk_predictions WHERE version_id='$V2' AND weekday=1 AND time_slot=0;" >/tmp/cc_c2_$RUN.out 2>&1 &
C=$!
wait_until "C2 contender blocked on lock" "$(contender_blocked)"
echo "      확인: 적재 세션이 Lock 대기 중"
$Q -c "INSERT INTO cc_gate VALUES ('open')" >/dev/null
wait $H; wait $C
echo "      delete 결과: $(grep -o 'FROZEN_VERSION:[^(]*' /tmp/cc_c2_$RUN.out || head -1 /tmp/cc_c2_$RUN.out)"
check "C2 동결 완료, 56행 유지" \
  "SELECT frozen_at IS NOT NULL AND loaded_row_count=56
          AND (SELECT count(*) FROM risk_predictions WHERE version_id='$V2')=56
     FROM prediction_versions WHERE id='$V2'"
grep -q 'FROZEN_VERSION:' /tmp/cc_c2_$RUN.out || { echo "FAIL  C2 기대 오류 FROZEN_VERSION 아님"; fail=1; }

rm -f /tmp/cc_c1_$RUN.out /tmp/cc_c2_$RUN.out
cleanup
[ $fail -eq 0 ] && echo "TOTAL: concurrency PASS" || { echo "TOTAL: concurrency FAIL"; exit 1; }
