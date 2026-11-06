# 지도 위험 구역 조회

`GET /api/v1/map/risk-zones` — API v0.10 / ERD v5.1 / Flyway V1~V5.
기존 Bearer JWT 인증을 사용한다. 위치 약관 검사와 위치 이용 로그 기록은 하지 않는다.
읽기 전용 API이며 사용자 데이터·규칙·모델을 변경하지 않는다.

## 실행 설정과 요청

기존 DB·JWT 설정은 DATABASE.md와 AUTHENTICATION.md를 따른다.
공개된 규칙 묶음 키를 `app.parking.rule-set`에 설정한다
(환경변수 `APP_PARKING_RULE_SET`). 기본값은 없으며 미설정·미공개·필수 규칙 누락은 503이다.
서버가 규칙을 자동 적재하거나 공개하지 않는다. `docs/rules_v1.sql`은 검토 후 운영자가 적용한다.

필수 쿼리는 `minLat`, `minLng`, `maxLat`, `maxLng`이며 WGS84 십진수다.
유한수·위도 -90~90·경도 -180~180·min ≤ max를 검사한다.
날짜변경선을 가로지르는 minLng > maxLng 표기는 지원하지 않는다.
선택 쿼리 `at`은 시간대가 있는 ISO 8601이다. `+`는 URL에서 `%2B`로 인코딩한다.
생략 시 기존 주입 Clock을 사용한다. 과거·미래 시각 제한은 없다.

각 변은 2,000m 이하(같으면 허용)다. PostGIS geography의 WGS84 타원체 거리로
위·아래 가로변과 세로변을 계산한다. 큰 경도 차이가 최단 경로로 축소되지 않도록
가로변은 경도 방향 4구간으로 나눠 합산한다. 각 구간은 90도 이하다.
같은 좌표로 퇴화한 범위도 허용한다. 잘못된 쿼리와 범위는 400 INVALID_INPUT이다.
두 축이 모두 같으면 Point, 한 축만 같으면 LineString, 나머지는 Polygon으로 만든다.
시설과 격자는 동일한 범위 식을 사용하며 각 도형의 경계를 포함한다.

## 응답

기존 `ApiResponse`의 `data`는 다음과 같다. 아래 숫자·날짜는 명세 예시이며 실행 기본값이 아니다.

```json
{
  "facilityZones": [
    {"zoneType":"HYDRANT","location":{"lat":37.3213,"lng":127.0961},"radiusM":5}
  ],
  "cells": [
    {"location":{"lat":37.3219,"lng":127.0972},"sizeM":50,"topPercent":8.0,"reportCount":12}
  ],
  "slot":{"weekday":6,"timeSlot":6},
  "dataPeriod":{"start":"2025-01-01","end":"2025-09-25"}
}
```

배열과 slot은 항상 존재한다. `dataPeriod`만 nullable이다. 시설·격자 ID나 경계 폴리곤은 응답하지 않는다.
location·반경·topPercent는 숫자, sizeM·reportCount·weekday·timeSlot은 정수다.
reportCount는 합산 오버플로를 피하도록 64비트 정수다.
OpenAPI에 성공 예시, 필수 키, nullable dataPeriod 및 400/401/500/503 응답을 등록했다.

요일은 KST 월요일 1~일요일 7, timeSlot은 KST 시간 / 3의 정수 몫이다.
활성 버전의 holiday_policy가 AS_SUNDAY이면 holidays에 있는 날짜를 7로 치환하고,
NONE이면 실제 요일을 쓴다. slot에는 예측 조회에 사용한 값을 반환한다.
알 수 없는 정책은 503이며, 정책을 임의로 AS_SUNDAY로 바꾸지 않는다.

활성 버전이 없으면 `cells=[]`, `dataPeriod=null`이다. 이 경우 slot의 공휴일 정책은
명세에 정의되어 있지 않다. 최소 기본안은 **공휴일 치환 없는 실제 KST 요일·시간 구간**이다.
dataPeriod는 선택한 버전의 training_start~observed_end이며 start/end 필드명을 쓴다.
latest 명세와 같은 모양이다. 현재 dev에는 latest 구현이 없어 구현 간 비교는 하지 못했다.

## 조회 기준

요청 전체는 읽기 전용 REPEATABLE_READ 트랜잭션으로 처리한다.
활성 예측 버전은 한 번만 선택하며 ID·원천·기간·임계값·공휴일 정책을 끝까지 함께 사용한다.
동결·검증 시각과 loaded_row_count = target_cell_count × 56을 확인한다.
규칙은 지정한 공개 묶음의 HYDRANT/BUS_STOP DISTANCE와 OTHER COVERS를 검증한다.
HYDRANT=5m·BUS_STOP=10m DISTANCE, OTHER=COVERS·거리 null·유한 비음수 금액의
검증은 #18 ParkingRuleContract와 일치시켰다. 응답 반경 자체는 규칙 행에서 읽는다.
공개 시각이 현재 Clock보다 미래이면 아직 공개되지 않은 것으로 처리한다.
#18은 published_at이 non-null인지 확인하므로 미래 공개 시각 처리에는 차이가 있다.
실제 공간 조회는 HYDRANT/BUS_STOP과 활성 data_imports만 사용하고 legal_distance_m을 반환한다.
OTHER나 어린이보호구역은 공간 조회에 포함하지 않는다.

명세는 화면 경계와 위험 반경이 겹치는 경우를 따로 정의하지 않았다.
현재 기준은 **시설 점이 화면 범위 내부 또는 경계에 있으면 포함**이다(ST_Covers).
시설 점이 밖이고 반경만 화면과 겹치면 제외한다. 소화전·정류소 자료는 Point여야 한다.
인덱스 후보는 원본 geography 컬럼의 `&&`를 사용한다.

격자는 boundary의 GiST 인덱스로 후보를 좁히고 ST_Intersects로 경계 접촉까지 포함한다.
prediction_target_grids의 해당 버전 대상 중 in_model_area=true이고 버전의 SRID·크기와
일치하는 격자만 반환 후보가 된다. #18의 OUT_OF_AREA 처리와 일치한다.
명세는 location 한 점만 지정하고 점의 의미는 설명하지 않는다. 현재 표시 기준점은
저장된 boundary의 ST_Centroid이며 응답은 명세의 `{lat,lng}` 구조를 유지한다.
이를 격자 원점으로 해석하면 안 된다. 중심점 의미는 #18 및 앱 연동 때 확인할 항목이다.
sizeM은 spatial_grids.cell_size_m을 사용한다.

화면 대상 격자 모두를 LEFT JOIN하여 예측 누락을 먼저 검사한다.
저장된 top_percent ≤ 버전 caution_top_percent인 칸만 cells에 넣으며 반올림 비교를 하지 않는다.
예측 행 누락·비유한 예측값·0~100 밖 백분위·음수 smoothed_count·잘못된 버전은 503이다.
주차 판정의 MISSING_ROW는 FAILED 데이터이지만 지도 응답에는 FAILED 상태 필드가 없으므로
이 최소 장애 정책으로 빈 칸을 안전으로 오해하지 않게 한다. DB 예외는 공통 500으로 전파한다.

## 신고 건수와 권한

기존 V4에서 parking_app은 report_aggregates·enforcement_records SELECT 권한이 없다.
권한·마이그레이션을 바꾸지 않고 조회 가능한 parcel_report_stats를 사용한다.
버전 source_import_id와 period_start=training_start, period_end=observed_end가 정확히 맞는
통계 스냅샷의 report_count를 합산한다. 요일·시간 필터와 event_group_count는 사용하지 않는다.
source_import의 현재 active 여부로 모델의 과거 계보를 바꾸지 않는다.

집계는 화면에 걸친 주의 칸별로만 수행한다. geography 공간 인덱스로 후보를 제한하고,
버전 projected_srid·cell_size_m에서 floor(x/size), floor(y/size)를 grid_x/grid_y와 비교한다.
격자 경계점은 floor 규칙에 따라 하나의 칸에만 들어간다.
정확한 원천·기간 스냅샷 자체가 없으면 503이다. 존재하는 스냅샷에서 해당 칸에 점이 없으면 0이다.
통계의 원천·기간은 확인하지만 적재된 개별 통계의 완전성까지 API에서 재검증하지는 않는다.
모델 ETL의 격자 집계와 이 통계 스냅샷의 동일성은 실제 데이터 검증 및 #18 통합의 확인 대상이다.

## 검증과 통합

```powershell
$env:JAVA_HOME='C:/Users/dlswn/.jdks/ms-21.0.13.0.2'
./gradlew.bat test bootJar compilePostgresTestJava --no-daemon
./scripts/db/Verify-MapRiskZones.ps1 -JavaHome $env:JAVA_HOME
```

검증 실행기는 기존 Verify-Authentication 방식의 새 tmpfs 컨테이너·임의 loopback 포트·
환경변수 허용 목록·SCRAM·DB identity 검사 방식을 따른다. .env와 Compose DB를 사용하지 않는다.
실제 V2~V5, bootstrap, rules_v1.sql을 임시 DB에 적용하고 공개 전 값을 확인한 뒤 공개한다.
합성 참조 데이터로 2km 경계, 활성 시설, OTHER 제외, 56조합 동결 모델,
임계값 포함, 통계 원천·기간 배제, KST 공휴일, parking_api SELECT를 검사한다.
검증 결과는 `build/map-verification/`에 보관하며 종료 시 생성한 컨테이너만 정지한다.

시간 규칙 원본은 현재 브랜치에 없지만 `feat/17-parking-events`의
`docs/schema/GRID_AND_TIME_RULES.md`에서 읽었다. #18 관련 참조 head는 `16c9e4b`다.
ParkingAssessmentEngine·ParkingRuleContract·ParkingController를 읽어 계산·규칙·오류 계약을 비교했다.
요일·공휴일·3시간·PostGIS floor 계산은 동일하다. 해당 컨트롤러는 POST만 구현하며 latest는 없다.
MapTime.slot과 MapRuleContract가 #18 병합 후 공통 코드로 연결할 지점이다.
두 패키지가 아직 dev에서 함께 존재하지 않으므로 기존 파일을 옮기거나 브랜치를 병합하지 않았다.
#18 판정의 시설 우선 1단계는 지도 노란 칸보다 우선한다. 노란 칸은 시설 판정을 제외한 모델의
주의 기준을 뜻하며 최종 주차 판정 전체를 대체하지 않는다.
#18은 현재 활성 시민신고 import와 모델 source 불일치를 FAILED로 처리한다.
지도는 요청 조건에 따라 모델 source_import_id를 직접 사용하므로 원천 활성화가 바뀌어도
모델 계보를 유지한다. #18의 반경 신고 건수와 지도의 칸 전체 건수는 서로 다른 통계다.
#22 PR head `9c8fd09`의 SecurityConfig·AgreementService도 읽었다.
동의 검사는 계정/위치 기능에 있으며 지도는 authenticated 필터만 통과한다.
두 브랜치를 수정하거나 병합하지 않았다. 실제 #18 HTTP 최종 판정과의 통합 테스트는 남아 있다.
현재 위치 이용 로그·주차 저장·계정·ETL 기능은 추가하지 않았다.

## 요청 범위와 로그

지도 요청 범위는 DB에 저장하지 않는다. 일반적인 `logging.level.org.springframework=DEBUG`
또는 앱 패키지 DEBUG 진단을 사용해도 URL·쿼리·바인딩 값을 출력하는 다음 개별 로거는
application.yaml에서 INFO로 유지한다: FilterChainProxy, DispatcherServlet,
ExceptionHandlerExceptionResolver, DefaultHandlerExceptionResolver, HandlerMethod,
InvocableHandlerMethod, RequestResponseBodyMethodProcessor. 반환 시설이 요청 모서리와 같은
좌표일 때 응답 객체 진단으로 노출되는 것도 막는다. 다른 로거와 인증 동작은 유지한다.
지도 전용 오류 처리기는 공통 오류 JSON·HTTP 상태·헤더를 유지하면서 예외 종류와 코드만 기록한다.
예외 원문·원인 체인·스택에는 요청값이 들어갈 수 있으므로 지도 오류 로그에 첨부하지 않는다.
다른 API의 기존 오류 로그 동작은 변경하지 않는다.

내장 Tomcat 접근 로그는 기본 비활성이다. 활성화할 경우 저장소의 패턴은
`%h %l %u %t "%m %U %H" %s %b`이며 `%U`는 경로만 기록한다. `%q`(쿼리),
`%r`(쿼리가 포함될 수 있는 요청 줄), Authorization 헤더를 추가하면 안 된다.
Compose에는 별도 HTTP 접근 로그 설정이 없다. **배포 시 외부 프록시·로드밸런서의 접근 로그에서도
쿼리와 인증 헤더를 제외해야 한다.** 이 저장소 밖 설정의 비노출을 보장하지 않는다.
개별 보호 로거를 명시적으로 다시 DEBUG/TRACE로 덮어쓰거나 JDBC 바인딩 TRACE를 활성화하는
설정도 보장 범위 밖이다. `spring.mvc.log-request-details=false`만으로 쿼리를 보호할 수는 없다.

MapRiskPrivacyTests는 실제 loopback HTTP 서버에서 Spring DEBUG·앱 DEBUG,
Tomcat 접근 로그 활성 상태로 정상·401·400·500·503을 요청하고 실제 로그를 캡처한다.
DB 예외·BusinessException 원인에 좌표·쿼리·토큰을 주입한 경우도 비노출을 검사한다.
통과한 캡처는 `build/map-verification/privacy-capture.log`, 경로만 있는 접근 로그는
같은 디렉터리 아래 `privacy-access-*`에 남는다.

덤프(`*.dump`, `*.backup`, 압축 SQL/덤프)와 `.env`, `build/` 산출물은 Git 제외 대상이다.
원본 덤프는 로컬에 보존한다. 이미 추적된 파일은 ignore 규칙만으로 추적 해제되지 않으므로
커밋 전 `git ls-files`와 `git diff --cached --name-only`도 확인한다.

공통 OpenAPI 테스트는 health의 존재·응답·보안을 검증하며 전체 API 경로 수를 고정하지 않는다.
지도 요청·응답·보안은 지도 전용 HTTP 테스트가 담당하므로 #18·#22의 새 경로와 독립적이다.

최대 2km 실행 계획은 덤프 검증 명령에 포함된다. 실제 API 거리 판정으로 두 변을 허용 경계까지
확장하고, `parking_api`로 운영 코드의 동일 SQL을 EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)한다.
결과는 `build/map-verification/max-2km-*-plan.json`에 SQL 리터럴을 제거하여 보관한다.
계획에는 실행 시간·행 수·인덱스·버퍼 정보가 남고 실제 범위 좌표는 남기지 않는다.

실제 덤프 검증은 다음과 같이 실행한다.

```powershell
./scripts/db/Verify-MapRiskZones.ps1 -JavaHome $env:JAVA_HOME -ReferenceDump docs/reference/yongin_parking_20261106.dump
```

참조 테이블 14개만 허용 목록으로 복원하며 사용자·차량·동의·이벤트·이용 로그와 덤프의
DDL·ACL·함수·Flyway 이력은 복원하지 않는다. 기존 SQL을 새 DB에 적용하고 보호 트리거를
유지한 채 미동결 모델 부모→대상/예측 자식→실제 행 수 검증·원본 동결 메타데이터 복원→활성화 순서로 처리한다.
pg_restore의 빈 search_path는 복원 세션에서만 public,pg_catalog로 바꾼다.
시설·모델의 실제 좌표나 사용자 데이터는 결과 로그에 출력하지 않는다.

검증된 범위: 일반 단위·HTTP/OpenAPI·실제 HTTP 로그 테스트 71개, 합성 PostGIS 통합 테스트 1개,
첨부 덤프 PostGIS 통합 테스트 1개가 통과했다(실패·오류·skip 0).
실제 활성 lgbm-yongin-v1의 대상 37,886칸·예측 2,121,616행을 동결 가드로 검증했으며,
실제 화면의 시설 7건과 주의 격자 66칸을 조회했다. 66칸의 신고 건수가 원천·기간이 같은
report_aggregates의 전체 요일·시간 합계와 모두 같았다. 지번 통계 5,593점과 격자 통계의
전체 기간 합계도 각각 44,154건이었다. 실제 API 서비스의 slot·기간과 동일 순간 UTC/KST
요청 응답도 같았다. 이는 위 덤프의 검증값이며 응답 기본값이나 새 데이터의 보장은 아니다.
보호 트리거를 유지한 덤프 보고서는 `build/map-verification/parking-map-9d8d74d3b5/`,
점·가로선·세로선·사각형·경계를 확인한 합성 자료 보고서는
`build/map-verification/parking-map-d22b85ccea/`에 있다.
GRID_AND_TIME_RULES의 수지구청 예시도 PostGIS floor 결과 (4173,10495)와 일치했다.

첨부 덤프의 최대 허용 2km × 2km 범위에서 parking_api로 측정한 실행 계획:

| 쿼리 | 계획 시간 | 실행 시간 | 결과 행 | 확인한 접근 방식 |
| --- | ---: | ---: | ---: | --- |
| 시설 | 19.561ms | 1.125ms | 141 | ix_facilities_spatial + 시설 유형 인덱스 BitmapAnd |
| 예측·신고 건수 | 11.843ms | 37.373ms | 1,356 | ix_spatial_grids_boundary, 대상/예측 PK, ix_parcel_stats_location |

격자 후보 1,681칸을 공간 인덱스로 좁힌 뒤 대상 1,356칸만 예측 PK로 조회했으며,
신고 통계 합산은 주의 기준을 만족하는 380칸에만 실행됐다. 예측 전체나 통계 전체의
순차 스캔은 없었다. 최대 범위는 각 축을 1e-9도 더 늘리면 실제 거리 검증이 거부하는 것도 확인했다.
위 수치는 같은 임시 DB에서 일부 선행 조회 후 1회 측정한 값이며 HTTP 총 지연·부하 성능 보장은 아니다.

확인한 문서 차이: reference/README와 DATABASE.md에는 여전히 API v0.6 설명이 있지만
이 엔드포인트는 첨부 v0.10 DOCX의 5절을 기준으로 한다. 예시 bbox의 위도 차이는 실제로
2km를 넘으므로 문서의 예시 URL을 그대로 요청하면 400이다. 성공 응답 예시와 범위 제한을
각각 따르며 이를 우회하는 예외를 추가하지 않는다.
