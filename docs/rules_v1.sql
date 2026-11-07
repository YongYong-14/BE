-- 규칙 묶음 최종안 (소화전 5m / 버스정류소 10m / 기타 COVERS).
-- 과태료: 승용차 기준 소화전 8만, 버스정류소 4만, 2단계(기타) 4만. 법령 별표 6 확인 후 공개할 것.
-- 공개(published_at) 후에는 수정·삭제 불가 → 마지막 UPDATE는 값을 확인한 뒤에만 실행.
-- 실행 계정: parking_migrator
BEGIN;

INSERT INTO zone_rule_sets (rule_set, description)
VALUES ('yongin-2026-11-v1', '용인 소화전 5m / 버스정류소 10m / 기타(2단계) 과태료 4만');

INSERT INTO zone_rules
 (id, rule_set, zone_type, geometry_rule, legal_distance_m,
  fine_amount_car, fine_condition, message_template_title, message_template_body)
VALUES
 (gen_random_uuid(), 'yongin-2026-11-v1', 'HYDRANT', 'DISTANCE', 5,
  80000, '승용차 기준',
  '소화전 근처 주차',
  '소화전에서 5m 이내입니다. 불법 주정차 단속 대상이 될 수 있어요.'),
 (gen_random_uuid(), 'yongin-2026-11-v1', 'BUS_STOP', 'DISTANCE', 10,
  40000, '승용차 기준',
  '버스정류소 근처 주차',
  '버스정류소에서 10m 이내입니다. 불법 주정차 단속 대상이 될 수 있어요.'),
 -- 2단계(신고 집중 칸)에서 앱이 보여 줄 예상 과태료 전용 행. 거리 판정에는 쓰이지 않는다.
 (gen_random_uuid(), 'yongin-2026-11-v1', 'OTHER', 'COVERS', NULL,
  40000, '승용차 기준, 일반 주정차 금지 구역',
  '불법 주정차 주의',
  '이 시간대에 신고가 많이 모이는 곳이에요.');

COMMIT;

-- 확인(공개 전): 3행이어야 함
-- SELECT zone_type, geometry_rule, legal_distance_m, fine_amount_car FROM zone_rules WHERE rule_set = 'yongin-2026-11-v1';

-- 값 확인 후 마지막에 실행(되돌릴 수 없음):
-- UPDATE zone_rule_sets SET published_at = now() WHERE rule_set = 'yongin-2026-11-v1';
-- 그리고 서버 설정: app.parking.rule-set = yongin-2026-11-v1
