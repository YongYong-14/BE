# 이슈 #17: 주차 저장·위험 판정 API

## 이슈 #25 조회·알림 표시 결과 보고

`feat/25-parking-event-queries`에서 아래 4개 API를 구현했다.
최신 DOCX의 파일명뿐 아니라 본문 v0.10 2·4·5·8절, DBML v5.1과 실제 Flyway V1~V5를 대조했다.
명세와 충돌하는 정책은 발견하지 않았다. 미정이었던 항목은 아래 백엔드 기본안으로 보완한다.

- `GET /api/v1/parking-events/latest`: 본인의 최근 기록. `detectedAt DESC, id DESC`로 선택한다.
  `event`는 id, detectedAt, confirmedLocation(lat/lng), address, vehicleName(저장 당시 차량명)을 포함한다.
  assessment는 status, level, predictionStatus 요약이다. 기록이 없으면 event와 assessment는 명시적 null이다.
  dataPeriod는 현재 활성 prediction_versions의 training_start/observed_end를 start/end로 반환한다.
  기록 유무와 무관하며 활성 버전이 없으면 null이다. 과거 판정을 재계산하지 않는다.
- `GET /api/v1/parking-events/{id}`: 본인 event와 POST와 같은 저장 assessment.
  stats에 상세 전용 peak를 추가한다. POST 응답은 변경하지 않는다.
  타인·없는 기록은 404 PARKING_EVENT_NOT_FOUND, 잘못된 UUID는 400 INVALID_INPUT이다.
- `GET /api/v1/parking-events?level=ALL&limit=50`: ALL/1/2, limit 1~100.
  본인 기록을 detectedAt DESC, id DESC로 반환하며 빈 목록은 items: []이다.
  ALL은 PARTIAL/FAILED/3단계를 포함한다. 목록은 대표 근거를 조인하는 단일 SELECT로 N+1을 피한다.
  응답 항목은 명세 4절의 event(id/detectedAt/address)와 assessment 요약을 따른다.
- `PATCH /api/v1/parking-events/{id}/delivery`: 알림 표시 결과를 보고하며 204, 본문 없음.

위 API는 기존 JWT와 @CurrentUser를 사용한다. 명세 2절·5절·8절 #15는 위치 동의 검사를
POST /parking-events와 GET /parking-lots/nearby에 적용한다. 이번 latest/상세/목록/delivery는
위치를 입력받거나 저장하지 않으므로 추가 동의 검사·위치 이용 로그를 적용하지 않는다.
조회는 저장된 판정·금액·문구·알림·evaluatedAt·attemptCount를 유지한다.
notify.expiresAt은 저장된 detectedAt + 30분이다. DB 장애는 공통 500이며 빈 결과로 바꾸지 않는다.

### delivery 계약 및 백엔드 기본안

요청 예: `{"deliveredAt":"2026-11-07T19:05:41+09:00","failure":null}` 또는
`{"deliveredAt":null,"failure":"NO_PERMISSION"}`. 생략은 null과 같으며 정확히 하나만 값이 있어야 한다.
둘 다 값이 있거나 둘 다 null, 잘못된 UUID·시각 형식은 400 INVALID_INPUT이다.

명세 v0.10 4절은 failure를 `NO_PERMISSION 등`으로만 설명한다.
이번 **백엔드 기본안**은 NO_PERMISSION / DISPLAY_FAILED / UNKNOWN 세 값만 허용한다.
빈 문자열·임의 문자열은 400 INVALID_INPUT이다.
deliveredAt은 Instant로 변환 후 마이크로초로 절삭하며 confirmedAt 이상,
서버 현재 시각(마이크로초 절삭) + 기존 미래 허용 오차(기본 2분) 이하여야 한다. 위반 시 400 INVALID_INPUT이다.
expiresAt 이후여도 서버 보고를 허용한다. 서버가 성공 시각을 생성하지 않는다.

저장 notify.decision이 SEND가 아니면 409 DELIVERY_NOT_EXPECTED, 타인·없는 이벤트는 404 PARKING_EVENT_NOT_FOUND다.
최초 실패를 보존하고 다른 실패는 무시한다(백엔드 기본안). 실패 후 성공은 성공 시각을 저장하고 failure를 지운다.
최초 성공 후 모든 유효한 보고는 기존 성공을 유지한다. 동일 보고도 204이고 중복 보고에도 입력·시각 검증을 적용한다.
이벤트 FOR UPDATE → 판정 FOR UPDATE 순서와 READ COMMITTED 트랜잭션으로 POST 재요청 및 동시 보고와 직렬화한다.
평가 결과·금액·문구·알림 결정·attemptCount·evaluatedAt·updatedAt은 delivery로 변경하지 않는다.

### stats.peak 백엔드 기본안

peak의 위치·요일·누락 슬롯 0·최다 슬롯·동점 시 이른 슬롯·저장 모델 원천 사용은
이번 작업 요청의 **백엔드 기본안**이다. 명세의 기존 확정 내용과 구분한다.
확정 좌표의 EPSG:5186/50m 칸은 기존 PostgisGridResolver로 조회한다(폴리곤 경계 포함 여부로 대체하지 않음).
detectedAt의 KST ISO 요일(월=1~일=7)과 저장 판정의 is_holiday, 참조 모델의 AS_SUNDAY/NONE 정책을 사용한다.
과거 is_holiday가 없는 경우만 기존 엔진과 동일하게 holidays를 확인한다.

저장 판정의 prediction_version_id/source_import_id와 참조 버전의 source_import_id가 일치해야 한다.
report_aggregates는 import_id·grid_id·weekday와 period_start/period_end를 함께 제한한다.
period_start/end와 training_start/observed_end는 모두 DATE다. 양쪽 날짜를 정확히 비교하며 반개방 타임스탬프 구간으로 변환하지 않는다.
source_availability의 observed_end는 해당 날짜까지의 완전 관측 종료일이라는 기존 스키마 계약을 따른다.
ETL 실행 코드는 이 브랜치에 없어 내부 날짜 필터 구현은 미검증이다. 기존 날짜 메타데이터를 재해석하거나 집계를 재생성하지 않는다.

slotCounts는 해당 요일의 슬롯 0~7 신고 건수 8개이며 누락 슬롯은 0이다. timeSlot은 최댓값의 슬롯, 동점은 이른 슬롯이다.
해당 요일 행 전체가 없거나 원천·기간·참조 버전·칸을 검증할 수 없으면 peak: null이다.
다른 원천·기간을 합산하거나 현재 활성 버전으로 대체하지 않는다. 희소 저장과 기간 포함 PK로 이 조건을 기존 스키마에서 표현할 수 있다.

### 실행 및 연결 지점

일반 검증: Java 21에서 `./gradlew.bat test compilePostgresTestJava bootJar --no-daemon`.
관련 DB 검증: `./scripts/db/Verify-ParkingEvents.ps1 -JavaHome <Java21경로>`.
기존 실행기는 전용 tmpfs PostGIS 컨테이너와 parking_api 역할을 사용하며 개발 DB·기존 볼륨을 사용하지 않는다.
실제 Swagger는 `/v3/api-docs`에서 확인한다. 공통 ApiResponse를 쓰며 nullable 필드도 생략하지 않는다.

**권한 자동 적용:** V4의 parking_app 참조 테이블 목록에 빠져 있던 report_aggregates 조회 권한은
`V6__parking_event_query_grants.sql`에서 `GRANT SELECT ON public.report_aggregates TO parking_app`으로 보완한다.
2026-11-07 origin/dev를 fetch해 현재 브랜치와 모두 V5까지임을 확인하고 미사용 V6를 선택했다.
기존 bootstrap은 parking_api를 parking_app에 소속시키고, 앱 실행 시 별도 parking_migrator 연결이 Flyway를 수행한다.
따라서 기존 V5 DB도 정상 앱 기동의 Flyway 단계에서 V6가 자동 적용되고, 런타임 parking_api는 SELECT를 상속한다.
새 DB에도 V1~V6가 순차 적용된다. 기존 V1~V5 및 DBML은 수정하지 않는다.
역할 생성·추가 역할 소속·다른 테이블 권한·집계 쓰기 권한은 부여하지 않는다.
수동 grants SQL은 제거했으며 별도 수동 적용 단계는 없다. 이미 수동 부여된 DB에서도 같은 GRANT는 안전하게 적용된다.
Flyway가 실패하면 기동 오류를 해결해야 하며 baseline/repair나 권한 높은 런타임 계정으로 우회하지 않는다.
이번 검증은 격리 DB에만 수행했다. 개발·운영 DB는 변경하지 않았다.

### 합성 데이터 검증과 실제 덤프 검증

합성 DB에서는 delivery 배타 입력, 세 실패 값, 시간 경계·마이크로초 절삭, 중복, 실패→성공,
최초 성공 보존, 성공/실패·성공/성공 동시 보고와 판정 보존을 검증한다.
peak는 정확한 원천·기간, 다른 기간/원천 배제, 집계 없음, 누락 슬롯, 동점, 공휴일 치환,
활성 버전 부재 및 참조 정보 부재를 검증한다. 실제 인증 필터를 거친 상세 HTTP에서 non-null peak도 확인한다.

실제 덤프는 원본을 변경하거나 Git에 추가하지 않고 새 tmpfs PostGIS 컨테이너에 복원했다.
기존 bootstrap → Flyway → 참조 테이블 16개 staging → FK 순서 적재 → 기존 freeze/activate 절차를 재사용했다.
원본의 동결·활성 메타데이터만 복원했으며 규칙 SQL을 적용·공개하지 않았다.
기존 보호 트리거·권한·함수의 복원 전후 fingerprint는 동일했다(당시 검증에서는 SELECT 권한을 별도 적용했으며 현재 적용 경로는 V6로 통일했다).
사용자·차량·주차 기록은 덤프에서 복원하지 않고 검증용 fixture만 만들었다가 제거했다.
비공개 규칙을 새로 만들지 않기 위해 실제 참조 검증 fixture의 판정은 FAILED로 만들고 해당 모델/원천을 참조하게 했다.
검증 후 prediction_versions와 report_aggregates는 staging 원본과 양방향 EXCEPT 비교로 전체 행이 동일함을 확인했다.

- 원천: `131e348e-128c-4380-a111-50502bcc8e74`.
- baseline-yongin-v1 / lgbm-yongin-v1 모두 training_start=2025-01-01, observed_end=2025-09-25.
- 실제 report_aggregates에는 같은 원천의 2025-01-01~07-31(14,511행), 01-01~09-25(17,861행),
  08-01~09-25(5,885행)이 있다. 정확히 일치하는 가운데 기간만 조회했다.
- 두 시연 좌표의 grid_id는 215018 / 215452다. 두 모델 × 두 좌표 × 7요일, 총 28개 상세 HTTP 응답 모두 non-null이며 직접 SQL 집계와 일치했다.
- 토요일 weekday=6의 결과:

| 시연 좌표 | slotCounts (0~7) | timeSlot |
| --- | --- | --- |
| 1 | [7, 2, 2, 9, 9, 21, 49, 22] | 6 |
| 2 | [5, 4, 5, 9, 20, 22, 69, 18] | 6 |

28개 표본 중 16개에는 누락 슬롯의 0 채움이 있었다. 최댓값 동점 표본은 없어 합성 데이터에서 검증했다.
이번 실제 표본에서 null은 없었다. 참조 검증 테스트는 null일 경우 NO_GRID_WEEKDAY_ROWS / SOURCE_MISMATCH / PERIOD_MISMATCH를 구분해 기록한다.
상세 결과는 `build/peak-reference/http-results.json`, 복원 건수는 `build/peak-reference/source-counts.json`에 있다(ignored 산출물).

실제 참조 검증 클래스는 ParkingPeakReferencePostgresTest다. 별도 격리 복원 DB의
`verification.identity=dump-trial-20261106`, DB명 parking_dump_trial, loopback 임의 포트를 확인하고 실행한다.
`PARKING_PEAK_REFERENCE_JDBC_URL=jdbc:postgresql://127.0.0.1:<격리포트>/parking_dump_trial`를 설정한 전용 환경에서
`./gradlew.bat postgresTest --tests '*ParkingPeakReferencePostgresTest' --no-daemon`을 실행한다.
공유 DB나 기존 Docker 볼륨을 이 환경 변수로 지정하지 않는다. 검증 종료 후 새 컨테이너만 중지한다.

최종 검증(2026-11-07): 일반 테스트 104개, 격리 합성 PostgreSQL/PostGIS 테스트 42개 통과.
실제 덤프 참조 테스트 1개(HTTP 비교 28건) 통과. postgresTest 컴파일·bootJar·git diff --check 통과.
권한 적용 경로 변경 후에도 일반 테스트 104개와 격리 합성 테스트 42개를 재실행해 통과했다.
격리 DB에서 실제 Flyway V5까지 적용 → parking_api SELECT 없음 → V6 1건 적용 → SELECT 상속 및
INSERT/UPDATE/DELETE/TRUNCATE/REFERENCES/TRIGGER 권한 없음 → 재실행 0건을 확인했다.
업그레이드 후 parking_api 연결의 상세 HTTP에서 non-null peak [0,0,7,0,7,0,0,0], weekday=6, timeSlot=2를 검증했다.
권한 변경 후 보고서는 `build/parking-event-verification/parking-parking-event-12b486069c/`다.
실제 덤프 테스트의 수동 GRANT도 Flyway 실행으로 교체하고 컴파일했다. 이 경로 변경에서는 덤프 복원을 반복하지 않았다.

#18 ParkingStore/기존 응답 매핑을 재사용하고, 인증은 현재 브랜치의 JWT에 연결한다.
미병합 #22/#24 코드는 병합하지 않는다. 기존 Flyway·DBML·ddl-auto 변경은 없으며 SELECT 권한 전용 V6만 추가했다.

## 기존 POST 구현과 검증 이력

`POST /api/v1/parking-events`의 인증·저장·판정·알림 응답과 커밋 후 비동기 주소 갱신을 구현했다.
신규는 201, 기존 이벤트 처리(FAILED 재판정 포함)는 200이다. 기존 ApiResponse를 사용한다.
공유 DB 직접 접속은 미확인이다. 전달된 덤프의 실제 LightGBM·시설 데이터는 별도 임시 DB에서 검증했다.
기존 POST 작업에는 조회·delivery·LLM·계정·ETL API를 포함하지 않았다. #25의 현재 상태는 위 절을 따른다.

## 확인한 기준

- `docs/reference/`의 최신 DOCX는 파일명과 본문 첫 제목 모두 API v0.10 / ERD v5.1이다.
  이번 보완은 4절의 OTHER 규칙·2단계 과태료·응답 계약을 적용한다. GPS 전송 정책 등 다른 v0.10 변경은
  이 규칙 보완의 구현 범위에 포함하지 않으며 별도 대조가 필요하다.
  본문 1절의 schema_v5/ERD v5, reference/README·README·DATABASE·AUTHENTICATION의 v0.6 표기는 오래된 안내다.
- ERD 본문은 v5.1이며 API v0.6 기준이라는 메타 정보가 남아 있다. 실행 기준은 실제 V1~V5 SQL이다.
  DBML·기존 마이그레이션은 수정하지 않는다.
- 새로 전달된 [격자·시간 계약](schema/GRID_AND_TIME_RULES.md)은 김민지 작성(2026-11-04),
  ERD v5.1 / API v0.6 표기다. 격자·시간·활성 버전 조회 계약을 v0.8과 대조해 적용했다.
- 루트·상위 경로와 저장소에서 AGENTS.md는 발견되지 않았다. README·COMMON_API 및
  DATABASE·AUTHENTICATION·JPA_AUDITING·VEHICLE_API·VERIFICATION_ISOLATION의 실제 규칙을 따랐다.
- 기존 운영 Java 코드는 공통 인증과 차량 기능이며, User/동의·카카오 Local·ETL 도메인 구현은 없다.

## 현재 처리 흐름

컨트롤러는 `@CurrentUser UUID`만 전달하고, `ParkingService`는 트랜잭션 프록시 호출이 끝난 뒤
응답 조립·오류 변환을 수행한다. `ParkingTransactions.save`가 트랜잭션 경계다.
입력에는 userId가 없고 알 수 없는 최상위 JSON 필드는 400으로 거절한다.

1. 요청 시각을 마이크로초로 절삭한 후 필수값·좌표·시간 관계·AUTO·마지막 좌표 나이를 검증한다.
   좌표의 원래 범위를 확인한 뒤 소수 7자리 반올림을 적용하고 정규화된 의미값으로 SHA-256을 계산한다.
2. 사용자 행 `FOR SHARE` → 최신 위치 동의 조회 순서다. 삭제된 사용자는 UNAUTHORIZED다.
   유형이 LOCATION_SERVICE인 행 중 `id DESC LIMIT 1`만 현재 상태로 사용한다.
   과거 AGREE를 찾기 위해 WITHDRAW나 허용되지 않은 최신 버전을 건너뛰지 않는다.
3. 기존 이벤트를 먼저 찾는다. 신규에만 미래 2분·PostGIS 보정 거리 검사 및 본인 활성 차량 `FOR SHARE`를 수행한다.
   기존 차량 변경과 같은 사용자 → 차량 잠금 순서다. 차량명은 신규 INSERT 시에만 복사한다.
4. `(user_id,client_event_id) ON CONFLICT DO NOTHING`으로 저장한다.
   기존 또는 동시 INSERT 패자는 이벤트 `FOR UPDATE` 후 해시·판정·시도 횟수를 다시 읽는다.
   다른 해시는 IDEMPOTENCY_KEY_REUSED, SUCCEEDED/PARTIAL 또는 FAILED 2회는 저장된 값을 반환한다.
   FAILED 1회만 같은 이벤트 잠금 안에서 2회로 재판정한다. 기존 요청에는 현재 차량을 재검사하지 않는다.
5. 설정의 공개 규칙 묶음, 활성 동결 예측 버전, 활성 import ID 목록을 한 SELECT의 스냅샷에서 고정한다.
   규칙 묶음 누락·미공개 또는 계약과 다른 법정 거리는 설정 오류로 전체 롤백한다.
6. 확정 좌표로 소화전 5m·버스정류소 10m만 geography 거리 검사한다. 해당하면 1단계/SKIPPED다.
   횡단보도는 추가하지 않는다. 여러 시설의 대표 순위는 아래 명시적 연결 설정을 사용한다.
7. 규칙 미해당이면 활성 버전 없음 → PARTIAL/NO_ACTIVE_VERSION,
   격자 없음·영역 밖 → PARTIAL/OUT_OF_AREA, 비대상 → PARTIAL/NOT_TARGET,
   대상이나 예측 행 없음 → FAILED/MISSING_ROW다.
   행이 있으면 원시 `top_percent <= caution_top_percent`로 2단계, 그 외 3단계다.
   실제 예측 0은 유효하며 누락과 다르다. NaN·Infinity·음수 예측·범위 밖 백분위는 FAILED다.
8. 이벤트·판정·근거·USE 로그가 같은 트랜잭션에 저장된다. 1·2단계의 대표 근거도 함께 저장한다.
   FAILED 재시도는 판정 ID와 created_at을 유지하면서 결과·근거를 교체하고 updated_at을 명시적으로 갱신한다.
   예상하지 않은 DB 오류는 전부 롤백한다. 실패한 트랜잭션 안에서 복구 쿼리를 하지 않는다.
9. 새 판정 또는 허용된 FAILED 재판정에서만 알림 결정·고정 문구를 같은 판정 행에 저장한다.
   기존 SUCCEEDED/PARTIAL/FAILED 2회 응답은 현재 알림 설정·활성 모델·현재 시각으로 재계산하지 않는다.
10. 신규 이벤트에 한해 트랜잭션 이벤트를 발행한다. AFTER_COMMIT 리스너가 별도 제한된 작업 큐로
    주소 갱신을 보낸다. 롤백·기존 요청은 주소 작업을 추가하지 않는다.

SQL/JDBC를 사용하므로 잘못된 시간 부모를 상속하는 엔티티를 만들지 않았다.
INSERT 시 실제 DB의 created_at/updated_at 기본값을 사용하고 UPDATE 시 시각을 직접 지정한다.
판정 버전·규칙 트리거, 지연 대표 근거 검사, 역할 권한과 purge 보호는 그대로 적용된다.
알려진 입력 오류는 DB 제약 실행 전에 검사하며, 모든 무결성 오류를 400/404로 바꾸지 않는다.

내부 Result에서 명세 4절의 공개 필드만 ParkingResponse로 변환한다. 내부 오류·사용자 ID·DB 원천 필드는 노출하지 않는다.
address 및 nullable 필드를 생략하지 않는다. primaryZoneType, referenceFineAmount, attemptCount,
evaluatedAt, reasons, stats, notify의 전체 필드를 포함한다. 근거에는 accuracyMarginM도 포함한다.
notify_decision·message_title·message_body·evaluated_at은 DB의 저장값을 반환한다.
실제 알림 표시 성공을 의미하는 delivered_at은 채우지 않는다.
1단계 과태료는 대표 시설 zone_rule의 `fine_amount_car`, 2단계는 같은 선택된 공개 묶음의
`OTHER.fine_amount_car`를 판정 트랜잭션에서 읽어 저장·반환한다(API v0.10 4절).
2단계 `primaryZoneType`과 MODEL 근거의 zoneType은 명세 예시대로 null이다. OTHER를 대표 시설로 표시하지 않는다.
3단계·PARTIAL·FAILED의 과태료는 null이다. Java의 금액 기본값은 없다.
이미 저장된 결과는 규칙/설정 변경 뒤에도 그대로 반환하며, 과거 null 금액도 재요청에서 채우지 않는다.
표시 정책은 v0.10에 반영됐지만 **법령별 금액 검증과 알림 문구 최종 확정은 별도 확인 항목**이다.

알려진 SQLSTATE·제약 이름 조합만 INVALID_TIME_ORDER, AUTO_CONFIRM_NOT_ALLOWED,
ADJUST_DISTANCE_EXCEEDED 또는 VEHICLE_NOT_FOUND로 변환한다. 다른 FK·CHECK·DB 오류는 공통 500이다.
정확한 PostgreSQL 제약 메타데이터를 읽기 위해 기존 JDBC 드라이버 의존성을 runtimeOnly에서
implementation으로 변경했으며 드라이버 버전은 그대로 Boot에 위임한다. 개인정보를 포함할 수 있는
SQL/바인딩/외부 API 예외 원문을 공통 로거에 연결하지 않는다.

## 만료 시각 재현성: 현재 계약에는 스키마 변경 불필요

앞선 중간 보고의 차이는 **만료 기간 자체가 향후 변경되는 가정**이었다. 현재 계약과의 불일치가 아니므로 정정한다.

| 테이블·컬럼 | 현재 용도 |
| --- | --- |
| parking_events.detected_at | 만료 시각의 불변 입력. expiresAt은 이 저장값 + 정확히 30분 |
| risk_assessments.notify_decision | 최초/허용 재판정의 SEND·억제·NONE 결정 스냅샷 |
| risk_assessments.message_title/message_body/evaluated_at | 문구·판정 시각 스냅샷. 기존 결과 반환 시 재작성하지 않음 |
| notification_settings.caution_enabled/night_enabled | 새 판정에서만 읽는 현재 사용자 설정 |

실제로 깨질 수 있는 구현 순서는 다음과 같다.

1. 주차 이벤트에 SEND와 당시 문구·판정 시각을 저장한다.
2. 사용자가 caution_enabled 또는 night_enabled를 바꾼다.
3. 같은 이벤트 재요청에서 현재 설정으로 결정·문구·판정 시각을 다시 계산한다.

3번을 하지 않으면 된다. 실제 구현은 이벤트 잠금 후 저장 결과를 반환하며 현재 설정을 읽지 않는다.
서버 시각이 만료를 지나거나 활성 모델이 교체되어도 저장된 SEND는 그대로다. 앱이 표시 시점에 만료 여부를 검사한다.
expiresAt은 최초와 재요청 모두 저장된 detected_at + 30분이며 now + 30분으로 계산하지 않는다.
따라서 **기존 컬럼만으로 현재 계약을 충족하며 이번 스키마 변경은 없다.**

향후 이벤트별 만료 기간을 가변으로 바꾸는 별도 계약이 생기면 최소 notify_expires_at timestamptz를
risk_assessments에 추가하고, 신규 판정 시 한 번 저장·기존 행은 당시 계약으로 채우는 변경을 검토할 수 있다.
현재 요구가 아니므로 이 컬럼이나 별도 TTL 설정은 추가하지 않았다. 상위 2% 경계도 현재 저장된
notify_decision의 재전송에는 필요하지 않다. 과거 경계 자체의 감사·재현은 별도 요구로 구분한다.

## 정규화·입력 계약

- 고정 순서의 타입별 스칼라 표현과 명시적 null 표식으로 SHA-256을 계산한다. JSON 키 순서는 무관하다.
- UUID는 파싱한 UUID 표현, 시각은 `OffsetDateTime.toInstant()`의 동일 순간으로 통일한다.
  시간대 없는 시각은 파싱에 실패한다. detectedAt·confirmedAt·lastKnownLocation.at은
  **마이크로초 미만을 절삭**하고 이 동일한 값을 검증·해시·저장·요일/시간대·야간·만료 계산에 사용한다.
  DB가 나중에 시각을 다음 구간으로 반올림하지 않도록 저장 전에 처리한다.
  예: 02:59:59.999999999 → 02:59:59.999999 (구간 0), 03:00:00은 그대로 구간 1이다.
  같은 마이크로초 안의 입력 정밀도 차이와 동일 순간의 다른 시간대 표현은 동일 해시다.
  서버의 현재 시각도 미래 허용·판정 비교 전에 마이크로초로 절삭한다.
  시간 순서·마지막 좌표 유효성 검사는 절삭된 시각끼리 비교한다. 따라서 마이크로초 미만의 역전은
  같은 시각으로 취급하며, 미래 허용 경계 +999ns도 절삭 결과가 같으면 허용하고 +1µs부터 거절한다.
- 좌표는 소수 7자리 HALF_UP, 음의 0도 0과 동일하다. 이 정규화 좌표를 저장·판정에도 사용한다.
- accuracyM은 유한한 0 이상 숫자이며 불필요한 소수 0을 제거한다. 좌표와 달리 7자리 반올림하지 않는다.
  해시 정규화에는 `stripTrailingZeros().toString()`의 짧은 지수 표기를 사용한다.
  1·1.0·1e0과 모든 0 표현은 각각 같은 값이며 지수 크기만큼 문자열을 펼치지 않는다.
  accuracyM=1e-1000000이나 BigDecimal의 최대 scale도 작은 문자열로 처리한다.
  극소 좌표는 7자리 반올림 결과가 0임을 먼저 판정해 거대한 10의 거듭제곱 계산을 피한다.
  실제 float8 컬럼에는 검증된 double 값으로 바인딩한다. 표현 범위보다 작은 양의 정확도는 0으로
  저장될 수 있지만, 해시는 요청의 정확한 의미값을 유지한다. 근거의 accuracy_margin_m도 같은 처리를 쓴다.
  새 업무상 숫자 범위 제한은 추가하지 않았다. JSON 파서의 기존 숫자 길이 제한과 BigDecimal의
  int scale 범위를 사용하며, 이를 벗어난 지수는 파싱 오류(400)다. 실제 OOM 테스트는 하지 않는다.
- 선택 위치의 생략과 null은 같다. 위치가 있으면 좌표 및 GPS accuracyM/마지막 위치 at이 필요하다.
  remeasureCount는 필수 정수이며 기본값을 해시에 몰래 추가하지 않는다.
- NaN·Infinity는 BigDecimal 입력/유한성 검사에서 거절한다. 좌표 범위는 반올림 전에 검사한다.
- 서버 시각·차량명·활성 버전·판정 결과는 해시에 포함하지 않는다.
  기존 요청에는 현재 시각과의 미래 비교를 반복하지 않는다.

숫자 표현과 시각 정규화는 이번 미출시 구현에서 확정한 해시 규약이다. 기존 단계의 임시 해시를
운영 DB에 적용한 적은 없다. 이미 다른 환경에 이전 구현으로 저장한 행이 있다면 새 규약과 혼용하기 전에
해시 호환 처리를 별도로 검토해야 하며 이번 작업은 개발·공유 DB의 행을 수정하지 않는다.

### 동일 키 저장과 차량 교체가 겹치는 경우

최초 이벤트 조회가 없었더라도 활성 차량 검증에서 VEHICLE_NOT_FOUND가 발생하면 같은 사용자의
동일 키 이벤트를 다시 조회한다. 이벤트가 생겼으면 기존 처리 경로의 FOR UPDATE로 합류하고
해시·상태·attempt_count를 다시 읽는다. 같은 본문은 저장된 결과, 다른 본문은 409를 반환한다.
여전히 이벤트가 없을 때만 404다. 일반 신규 요청의 차량 소유권·활성 조건은 유지한다.
이 재조회는 애플리케이션 검증 실패 뒤에만 수행하며 SQL 오류로 실패한 트랜잭션에서는 수행하지 않는다.

GPS 정확도에 따른 등급 정책은 미결이다. v0.8의 임시 정책대로 확정 좌표로 거리를 계산하고
시설 근거의 accuracy_margin_m에 GPS 정확도를 기록할 뿐 등급을 바꾸지 않는다.
20m 기준, 수동 좌표 무조건 신뢰, 정확도가 나쁘면 안전 처리 등의 정책을 추가하지 않았다.
기준점은 original 우선·없으면 lastKnown이며, 둘 다 없으면 수동 확정만 거리 제한 없이 받는다.

## 계정·데이터 담당자 연결 설정

| 소유/설정 | 연결 계약 |
| --- | --- |
| 계정: `app.account.location-versions` ← `AUTH_LOCATION_VERSIONS` | 순서 있는 쉼표 구분 목록. 첫 값만 현재 버전이며 `AgreementPolicy`는 이 값과 최신 AGREE를 비교한다. 기본 약관 버전 없음. 빈 설정이면 모든 위치 요청을 403으로 거절한다. |
| 데이터/운영: `app.parking.rule-set` | 공개된 실제 zone_rule_sets.rule_set. 최신 날짜나 임의 이름을 자동 선택하지 않는다. 기본값 없이 실행 시 검사한다. |
| 데이터/기획: `app.parking.facility-priority` | 시설이 여러 유형에 걸릴 때의 합의된 우선순위 목록. 기본 순위 없음. 미설정이면 겹친 건은 FAILED/FACILITY_PRIORITY_UNCONFIGURED. 테스트의 HYDRANT,BUS_STOP은 fixture 설정이다. 같은 유형은 거리→시설 UUID로 결정한다. |
| 데이터: 활성 prediction_versions | ID·버전명·caution_top_percent를 매번 선택된 활성 행에서 읽는다. 베이스라인/LightGBM 조회 경로가 같다. |
| 데이터: import | 새 계약의 활성 `yongin_enforcement` ID와 선택 모델의 source_import_id가 다르면 FAILED/SOURCE_IMPORT_MISMATCH. 통계와 예측 출처를 섞지 않는다. |
| 데이터: 격자·공휴일 | 문서의 PostGIS 식, 5186·50m·투영 좌표 원점 0의 floor. grid_id는 spatial_grids 조회값이며 계산값이 아니다. holidays는 데이터 담당자가 적재한다. |

공휴일 정책은 선택된 버전의 AS_SUNDAY/NONE을 사용한다. weekday_actual은 치환 전 ISO 요일,
weekday는 조회용 요일, time_slot은 detectedAt의 KST hour/3이다.
스무딩은 서빙에서 다시 계산하지 않고 그 버전의 smoothed_count를 사용한다.
percentile_basis와 GE_SHARE 동점 정책도 버전의 계산 결과를 그대로 사용한다.

다음 값은 명세의 임시값이며 Spring 설정으로 관리한다.

| 설정 | 기본값 |
| --- | --- |
| `app.parking.future-tolerance` | 2m |
| `app.parking.last-known-max-age` | 10m |
| `app.parking.max-remeasure-count` | 10 (스키마·명세 범위 내에서만 설정) |
| `app.parking.report-radius-m` | 50 |
| `app.parking.immediate-top-percent` | 2 (선택한 예측 버전 caution_top_percent 이하인지 검사) |

30분 만료는 최신 API의 고정 계약으로 사용하며 변경 가능한 중복 TTL 설정을 만들지 않았다.
모델은 DB의 active 선택 하나만 사용한다. 규칙 묶음에는 active/대표 선택 컬럼이 없으므로 공개된 묶음의
명시적 선택 설정이 필요하다. facility-priority도 실제 스키마에 순위 컬럼이 없어 필요한 연결 설정이다.
계정 담당 구현은 아직 없으며 AgreementPolicy·최소 최신 동의 조회와 기존 notification_settings 테이블을 재사용한다.
별도 User 엔티티·알림 설정 CRUD·중복 약관 버전 설정은 만들지 않았다. 알림 설정 행이 필요한데 없으면
가입 불변식 오류로 전체 롤백/500이며 true/false 기본 행을 생성하거나 추측하지 않는다.

### #22와 위치 동의 버전 계약 통일

**위치 동의 현재 버전은 계정 모듈 설정 AUTH_LOCATION_VERSIONS의 첫 값이며,
시연 환경에는 합의된 현재 버전 하나만 넣는다.** 실제 버전값은 계정 담당자·앱과 합의해야 하며 임의 기본값은 없다.
`application.yaml`의 단일 `app.account.location-versions: ${AUTH_LOCATION_VERSIONS:}` 연결을 사용한다.
`AgreementPolicy`는 항목 앞뒤 공백과 빈 항목을 제거하고 중복을 최초 등장 순서대로 제거한 List로 파싱한다.
빈 설정·null·미등록 버전뿐 아니라 목록의 두 번째 이후 버전도 현재 위치 동의로는 인정하지 않는다.
이전 설정 경로를 대체 경로로 읽지 않는다.
종전의 순서 없는 집합에 포함되기만 하면 위치 동의를 인정하던 판단을 첫 값 기준으로 변경했다.

[재원님 #22](https://github.com/YongYong-14/BE/pull/22)의 head
`9c8fd0955f7c218d29f9bca93aae33592f9d01ad`에서 다음 실제 구현을 읽기 전용으로 대조했다.

- `AgreementService`는 같은 설정 키의 문자열을 `trim → 빈 항목 제거 → distinct → List`로 파싱한다.
  계정 API의 **AGREE 접수**는 목록 전체에 포함된 버전을 허용한다.
- `AgreementService.acceptedCurrent`는 목록 첫 값과 같은 버전의 최신 AGREE만 현재 동의로 인정한다.
  주차 API의 `acceptsLocationVersion`은 이 현재 버전 판단에 해당하며 접수 허용 목록 검사와 다르다.
- `UserAgreementRepository.findLatestByType`은 유형별 최대 id 이벤트를 선택한다.
  현재 주차 코드의 사용자 FOR SHARE → LOCATION_SERVICE의 id DESC LIMIT 1 조회도 같은 최신 이벤트를 고른다.
  최신 WITHDRAW나 이전 버전 AGREE를 건너뛰어 과거 현재 버전 AGREE를 허용하지 않는다.
- #22의 `application.yaml`도 AUTH_LOCATION_VERSIONS를 같은 키에 연결한다. 설명된 목표 계약과 실제 구현의 차이는 없다.
  단, #22는 위치 철회·파기 정책 미결로 해당 계정 API 요청을 503 처리한다. 주차의 기존 WITHDRAW 거부는 유지한다.

#22 코드는 수정·병합하지 않았고 계정 서비스·동의 API도 현재 브랜치에 복제하지 않았다.
**#22와의 코드 통합은 아직 완료되지 않았다.** 이후 #22가 dev에 머지되면 최신 dev 반영 시 충돌을 다시 확인한다.
설정 병합 때 `app`·`account` YAML 키나 location-versions 연결을 중복 선언하지 않고
#22의 다른 account 설정과 현재의 독립된 kakao.local 설정을 함께 보존해야 한다.
`OpenApiTests.java`의 현재 검증은 변경하지 않는다. 향후 통합 시 양쪽 API·보안·응답 검증을 모두 보존하고
API 개수만 맞추기 위해 검증을 삭제하지 않는다.

현재 버전을 바꾸면 기존 주차 재요청도 최신 동의부터 다시 확인하므로 새 현재 버전에 동의하기 전에는 403이다.
새 버전에 AGREE한 뒤 같은 키를 재요청하면 기존 차량·알림 설정과 관계없이 저장된 판정을 그대로 반환한다.
계정 담당 코드의 실제 통합 검증은 아직 남아 있다.

## 실행 전 설정·참조 데이터 점검

| 설정 | 실행 환경 연결 | 확인할 계약 |
| --- | --- | --- |
| `app.parking.rule-set` | `APP_PARKING_RULE_SET` 또는 같은 이름의 Spring 실행 속성 | 데이터 담당자와 합의한 실제 공개 묶음. 값이 없거나 미공개/미존재이면 설정 오류다. 최신 날짜의 묶음을 임의로 선택하지 않는다. |
| `app.parking.facility-priority` | `APP_PARKING_FACILITY_PRIORITY` 또는 같은 이름의 Spring 실행 속성 | 여러 시설 유형이 겹칠 때의 합의된 CSV 순서. 임의 순서를 기본값으로 넣지 않는다. 미설정 다중 유형 겹침은 기존 FAILED/FACILITY_PRIORITY_UNCONFIGURED이며, 단일 유형 안의 거리→UUID 순서는 유지한다. |
| `app.account.location-versions` | `AUTH_LOCATION_VERSIONS` | 첫 값이 합의된 현재 버전. 시연에는 그 버전 하나만 사용한다. 빈 설정이면 동의 행이 있어도 403이다. |

셸의 `.env` 로더와 IDE 실행 환경 주입은 DATABASE.md의 절차를 따른다. `.env` 파일이 있다고
이미 실행된 JVM에 설정이 자동 반영되는 것은 아니다. 변경한 설정은 해당 앱 프로세스를 다시 실행해 반영한다.
실제 비밀값이나 전체 환경변수를 출력하며 점검하지 않는다. 설정을 채우기 위해 동의·규칙·시설·예측 데이터를
임의로 만들지 않고, 필요한 합의값/적재 상태를 담당자에게 확인한다.

참조 데이터는 연결 대상 DB를 확인한 뒤 읽기 전용 계정·트랜잭션으로 점검한다. 다음은 psql 예시이며
입력값은 현재 앱에 설정한 합의된 rule-set이다. 관리용 publish/freeze/activate/purge 명령은 포함하지 않는다.

```sql
\prompt 'Configured app.parking.rule-set: ' selected_rule_set
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT current_database(), current_user;

SELECT rule_set, published_at
FROM public.zone_rule_sets WHERE rule_set = :'selected_rule_set';

SELECT zone_type, geometry_rule, legal_distance_m, fine_amount_car
FROM public.zone_rules
WHERE rule_set = :'selected_rule_set' AND zone_type IN ('HYDRANT','BUS_STOP','OTHER')
ORDER BY zone_type;

SELECT p.model_version, p.frozen_at, p.validated_at, p.target_cell_count,
       p.loaded_row_count, p.loaded_row_count = p.target_cell_count::bigint * 56 AS complete_metadata,
       p.caution_top_percent, p.source_import_id,
       p.source_import_id = (
         SELECT id FROM public.data_imports
         WHERE source_dataset = 'yongin_enforcement' AND active
       ) AS matches_active_statistics_import
FROM public.prediction_versions p WHERE p.active;

SELECT t.zone_type, EXISTS (
  SELECT 1 FROM public.facilities f JOIN public.data_imports d ON d.id = f.import_id
  WHERE d.active AND f.zone_type = t.zone_type
) AS has_active_facilities
FROM public.zone_types t WHERE t.zone_type IN ('HYDRANT','BUS_STOP');
COMMIT;
```

규칙 묶음은 1행이고 published_at이 있어야 한다. 필요한 규칙은 소화전 DISTANCE/5m·버스정류소
DISTANCE/10m·OTHER COVERS/거리 null의 유형별 1행씩이다. OTHER 금액은 null·음수가 아니어야 한다.
누락·중복·잘못된 구성은 FACILITY_RULE_CONTRACT_MISMATCH 진단과 공통 500으로 전체 롤백한다.
실제 중복은 기존 (rule_set, zone_type) UNIQUE도 차단한다. 기본 금액으로 대체하지 않는다.
OTHER는 금액/문구 메타데이터이며 공간 후보에서는 제외한다. 어린이보호구역 등도 1단계에 추가하지 않는다.
시설 존재 여부는 적재 전체의 완전성을 증명하지는 않는다.
활성 예측 버전은 동결·검증 시각과 완전성 메타데이터 및 통계 원천 일치를 확인한다. 실제 적재 완전성은
기존 동결 함수·보호 제약과 데이터 담당자의 검증을 따른다. 제약을 끄거나 비동결 모델을 활성화하지 않는다.
활성 버전이 없는 경우는 기존 PARTIAL/NO_ACTIVE_VERSION이며 설정 오류로 바꾸지 않는다.
일부 데이터만 있거나 원천이 다르면 담당 적재 상태를 확인하고 임시 성공 결과로 대체하지 않는다.

위치 동의는 AUTH_LOCATION_VERSIONS의 첫 값과 사용자별 최신 LOCATION_SERVICE 이벤트의
version·action을 대조한다. 최신은 occurred_at이 아니라 최대 id 기준이다. 해당 사용자만 조회하고
최신 이벤트가 현재 버전 AGREE인지 확인하며 WITHDRAW나 이전 버전 AGREE를 건너뛰지 않는다.
이 조회를 위해 토큰·비밀번호·원본 좌표를 출력하거나 동의 데이터를 임의 추가하지 않는다.

### 안전한 서버 진단 코드

설정 검증 실패는 요청 시 기존 트랜잭션에서 거절·롤백하고, 서비스에서 다음 고정 코드만 ERROR로 기록한다.
API는 세 경우 모두 기존 `500 INTERNAL_SERVER_ERROR`이며 내부 코드를 응답하지 않는다.

| 내부 로그 코드 | 기존 거절 조건 |
| --- | --- |
| `PUBLIC_RULE_SET_UNAVAILABLE` | 설정된 공개 규칙 묶음이 선택되지 않음: 누락·미존재·미공개 포함 |
| `MODEL_VERSION_NOT_FROZEN` | 선택된 활성 모델의 동결 시각 누락. 정상 스키마에서는 기존 DB 제약도 방어함 |
| `FACILITY_RULE_CONTRACT_MISMATCH` | 공개 묶음의 MVP 시설 유형·거리 규칙 계약 불일치 |

로그 형식은 `Parking configuration rejected: code=<고정 코드>`다. 원래 IllegalStateException 메시지,
원인 체인, 설정값·원본 SQL·바인딩 값·좌표·토큰·비밀번호·외부 응답은 붙이지 않는다.
알 수 없는 IllegalStateException도 메시지를 무조건 출력하지 않는다.
MISSING_ROW 등 예상한 판정 장애의 기존 FAILED/success=true 응답은 이 설정 오류와 구분해 유지한다.
기동 시 검증이나 DB 사전 연결을 새로 추가하지 않았으며, 기존 프로필·lazy 빈·DB 없는 테스트의 동작을 유지한다.

## 알림 결정과 문구

### rules_v1.sql 적용 전 점검

첨부 [rules_v1.sql](rules_v1.sql)(schema/rules_v1.sql)은 `yongin-2026-11-v1`의 HYDRANT/BUS_STOP/OTHER
3행을 INSERT하고 COMMIT한다. 자동 공개는 하지 않으며, 파일 끝 공개 UPDATE는 주석이다.
**단순 INSERT이므로 재실행은 실패한다.** 같은 rule_set이 있으면 내용과 공개 상태를 읽어서 비교한 뒤 중단하며,
ON CONFLICT로 무시하거나 공개된 규칙을 수정하지 않는다. 다음 조회는 실행할 대상 DB를 먼저 확인한 뒤 수행한다.

```sql
SELECT zone_type FROM public.zone_types WHERE zone_type IN ('HYDRANT','BUS_STOP','OTHER');
SELECT rule_set, published_at FROM public.zone_rule_sets WHERE rule_set='yongin-2026-11-v1';
SELECT zone_type, geometry_rule, legal_distance_m, fine_amount_car
FROM public.zone_rules WHERE rule_set='yongin-2026-11-v1' ORDER BY zone_type;
```

유형 3개가 있어야 하고 신규 적용 전 묶음/규칙 조회는 0행이어야 한다. 임시 DB 시험 순서는
bootstrap/Flyway → 참조 데이터 → SQL 적용 → 미공개 3행과 값 확인 → 임시 DB에서만 공개 →
서버 `app.parking.rule-set`에 해당 묶음 연결이다. SQL의 실행 역할은 parking_migrator이며 소유권·권한을 바꾸지 않는다.
시설 우선순위와 현재 약관 버전에는 합의된 설정만 넣는다. 이 절차는 개발 DB 적용을 자동 승인하지 않는다.

SQL의 HYDRANT/BUS_STOP `message_template_title`은 실제 1단계 제목으로 사용한다.
SQL의 시설 설명 본문과 OTHER 제목/본문은 메타데이터로 보존하지만, API v0.10 4절이 명시한
통계 기반 1단계 본문과 고정 2단계 제목·요일/구간/백분위 본문을 대체하지 않는다.
OTHER 행의 금액은 2단계에 적용하며 공간 판정에는 사용하지 않는다. SQL 문구와 API 생성 문구의 차이는
이처럼 구분하고, 법령 금액 검증과 문구 최종 확정은 기획·담당자 확인으로 남긴다.

우선순위는 감지 후 30분 초과 NONE → 1단계 SEND → 2단계 원시 top_percent가 즉시 경계 이하이면 SEND →
일반 2단계 caution_enabled=false면 SUPPRESSED_SETTING → KST 22:00 이상 또는 07:00 미만이고
night_enabled=false면 SUPPRESSED_NIGHT → SEND다. 3단계·PARTIAL·FAILED는 NONE이다.
30분 정확히 경계는 서버의 오래됨 제외에 해당하지 않으며 앱은 실제 표시 시 expiresAt을 확인한다.
반올림한 표시값으로 상위 2% 여부를 계산하지 않는다.

1단계 제목은 대표 zone_rule의 템플릿을 사용하고 없으면 실제 시설 라벨을 고정 문장에 넣는다.
본문은 실제 통계 건수·기간만 포함한다. 모델 문구는 조회용 요일·3시간 구간·실제 백분위를 사용하며
격자 근사값임을 표시한다. LLM, 단속 확률, 데이터에 없는 과태료 납부 인원 표현은 없다.

## 커밋 후 비동기 역지오코딩

로그인 클라이언트가 없어 독립된 최소 Kakao Local 클라이언트를 추가했다.
[공식 좌표→주소 REST 규격](https://developers.kakao.com/docs/ko/kakaomap/rest-api)을 따른다.
새 설정은 app.kakao.local.rest-api-key이며 환경변수 KAKAO_LOCAL_REST_API_KEY로 연결한다.
기존 키 계약이 없어 이 기능 전용 이름을 정의했으며 JWT·카카오 어드민 키와 혼용하지 않는다.
키가 비어 있으면 전송하지 않고 저장·판정과 address:null 응답은 정상 동작한다.

별도 스레드·새 트랜잭션에서 사용자 FOR SHARE → 최신 동의 확인 → 이벤트 FOR UPDATE 순서로 확인한다.
주소가 이미 있거나 이벤트가 삭제되었으면 호출하지 않는다. 외부 전송 직전 동의를 확인한 사용자 잠금을
최대 2초 제한 외부 요청 동안 유지하므로 철회·탈퇴가 전송 중간에 앞지를 수 없다.
키가 있으면 PROVIDE/주소 변환 요청/Kakao Local 사실 로그를 남긴다. 실패해도 최초 주차 트랜잭션을 롤백하지 않는다.
주소만 조건부 UPDATE하며 INSERT/이벤트 복구·판정·알림 변경은 하지 않는다.
주소 응답이 없거나 네트워크 실패면 null을 유지한다. 키·URL/좌표·주소·외부 오류 원문은 로그에 남기지 않는다.

AFTER_COMMIT은 DB 커밋 후 실행을 보장하지만 HTTP 응답의 실제 전송 완료 후 실행은 보장하지 않는다.
최초 응답은 비동기 작업 이전의 스냅샷이므로 주소 null일 수 있다. 프로세스 내부의 제한 큐이므로
서버 종료·큐 포화 시 주소 갱신은 유실될 수 있으며 영속 작업 큐나 자동 재시도 보장은 이번 범위가 아니다.
외부 위치 전송 고지·법적 범위는 미결이며 구현 완료를 고지 합의 완료로 기록하지 않는다.

신고 통계는 선택한 import의 parcel_report_stats 좌표 스냅샷을 geography 반경으로 합산한다.
원천 전체 COUNT나 현재 geocoded_parcels 좌표를 사용하지 않는다. 기간은 합산한 통계 행의 MIN/MAX다.
해당 통계 행이 없으면 문서의 SUM 계약대로 건수 0, 기간 null이며 날짜를 만들어 채우지 않는다.
모델 예측은 격자 중심 스무딩 근사값이고 사용자 좌표 중심의 정확한 반경 통계·단속 확률이 아니다.

## 실제 공유 데이터의 확인 상태

전달받은 상태: baseline-yongin-v1, 37,886 대상 격자·2,121,616 예측 행, freeze/activate 완료,
현재 caution_top_percent=10, 샘플 1 토요일 19시의 top_percent 약 0.00.
이는 전달 정보이며 이 작업에서 공유 DB로 조회해 확인한 결과는 아니다.
현재 공유 DB 호스트·포트·DB명·읽기 전용 계정/접속 전달 방식·덤프 위치는 제공되지 않았다.
로컬 `.env`나 개발 DB에 데이터가 있다고 가정해 접속하지 않았다.

읽기 전용 계정과 안전한 인증 전달 방식 또는 다음 데이터의 일관된 스냅샷이 필요하다.
활성 예측 버전·대상 격자·예측 행, spatial_grids, holidays, 공개 규칙·zone_types,
활성 시설과 data_imports, parcel_report_stats·source_availability 및 스키마 버전 정보다.
비밀번호·API 키를 문서나 콘솔에 출력하지 않는다.
[읽기 전용 점검 SQL](../scripts/db/Inspect-ParkingPrediction.sql)은 READ ONLY/REPEATABLE READ에서
활성 버전, 두 샘플의 격자·원시 top_percent·버전 경계와 시설 일치를 따로 조회한다.
공유 데이터 수정·동결·활성화 변경 명령은 없다. 전체 최종 판정에는 실제 공개 규칙 선택 설정도 필요하다.

## 검증과 남은 실제 연동

```powershell
$env:JAVA_HOME='자신의 Java 21 JDK 경로'
./gradlew.bat test compilePostgresTestJava --no-daemon
./scripts/db/Verify-ParkingEvents.ps1 -JavaHome $env:JAVA_HOME
```

실행기는 기존 Verify-Vehicle의 환경 초기화·고유 tmpfs·loopback 임의 포트·SCRAM·DB identity
보호를 재사용한다. 명시된 parking_event_verify DB에서만 관리자가 역할을 준비하고,
테스트는 DDL 실행 전에 DB/역할/임의 identity를 확인한다. 실제 V1~V5를 migrator로 실행하고
API 동작은 parking_api 권한으로 검증한다. 개발 DB·Compose·기존 볼륨·`.env`를 쓰지 않는다.
PostgreSQL fixture는 완전한 56조합을 적재해 실제 freeze/activate 함수를 거친다.
MISSING_ROW는 조회 경계의 테스트 대역으로 주입하며 동결 가드를 끄거나 실제 동결 행을 훼손하지 않는다.
보고서는 `build/parking-event-verification/<고유 컨테이너>/`에 남고 자신의 컨테이너만 정지한다.
이 새 PowerShell 실행기는 Windows용이며 Mac에서는 미검증이다.
자식 JVM의 AUTH_LOCATION_VERSIONS는 격리 fixture인 `parking-test-version` 하나로 명시한다.
부모의 배포용 약관 목록을 상속하지 않으며 환경 초기화·OS 변수 허용 목록·임시 DB 식별 검사는 유지한다.

남은 실제 연동은 공유 DB의 버전명/ID·원시 백분위·시설·최종 판정 확인, 합의된 약관 버전·공개 규칙·
시설 우선순위 설정 제공, 실제 Kakao 키와 네트워크 확인이다. 자동 테스트는 외부 호출을 대체한다.
아직 없는 계정 철회·탈퇴 API 전체와의 HTTP 통합은 미검증이며 사용자 잠금 계약을 지켜 연결해야 한다.

커밋·푸시·PR 생성은 하지 않는다.

### 1·2단계 중간 실행 결과 (2026-11-06, 이후 단계는 아래 최종 결과 참조)

- 일반 테스트 66개 통과, 실패·오류·skip 0. JSON 키 순서·선택값 null·숫자·시각 정규화와
  입력 경계 검증을 포함한다.
- PostgreSQL 16.15 / PostGIS 격리 통합 테스트 15개 통과, 실패·오류·skip 0.
  보고서: `build/parking-event-verification/parking-parking-event-0b2a7e67d2/`.
- 실제 PostGIS 계산: 샘플 1 `(4170,10500)`, 샘플 2 `(4171,10501)` 모두 기대값과 일치.
  음수 floor와 정수 경계, KST 토요일 19시의 실제 요일 6·시간대 6, 공휴일 치환도 확인했다.
- 격리 모델 fixture의 원시 top_percent=1.23456789, 해당 fixture 버전의 경계=10을 비교해 2단계다.
  같은 좌표에 소화전 fixture가 있으면 시설 거리로 1단계가 우선한다.
  **이 값들은 공유 baseline-yongin-v1의 실측값이 아니다.**
- 동시 신규 저장, 동시 FAILED 재시도 최대 2회, 실패 후 복구, 재판정 중 롤백,
  차량 변경 후 재전송, 최신 동의 id, 철회 잠금 대기, 차량 교체 대기,
  앱의 예측 변경 권한 거부 및 커밋 시 대표 근거 누락 거부를 확인했다.
- HTTP/Swagger·알림 상위 2%·역지오코딩·실제 공유 데이터는 미검증이며 다음 단계로 남긴다.

### POST·알림·주소 연결 최종 검증 (2026-11-06)

- `test bootJar compilePostgresTestJava --no-daemon`: 성공. 일반 테스트 80개, 실패·오류·skip 0.
  최종 빌드는 직전 통과한 일반 테스트 결과를 UP-TO-DATE로 재사용했다.
- 격리 PostgreSQL/PostGIS 통합 테스트 21개, 실패·오류·skip 0.
  최종 보고서: `build/parking-event-verification/parking-parking-event-1d94ec260b/`.
- 실제 AccessTokenFilter·JdbcUserExistence·CurrentUserResolver·컨트롤러·트랜잭션·DB로
  신규 201, 재요청 200, 동의 403, 차량 404, 멱등키 409 및 공통 응답을 확인했다.
  별도 전체 Spring MVC/보안 컨텍스트 테스트에서 미인증·입력 오류·nullable 필드·Swagger도 확인했다.
- 오래됨 최우선, 1단계·2% 예외, 설정→야간 억제 우선순위, KST 22시·7시,
  원시 2.00000001과 정확히 2의 구분, 30분 정확한 경계·초과를 검증했다.
- 설정·활성 모델·현재 시각 변경 후 기존 판정·알림 결정·문구·만료 시각이 유지되는 것을 확인했다.
- 커밋 후에만 작업 등록, 롤백·기존 요청의 미등록, 주소만 변경, 실패·키 없음·삭제 이벤트 처리,
  전송 전 동의 재확인과 전송 동안 사용자 잠금에 의한 탈퇴 대기를 실제 DB에서 확인했다.
  HTTP 전송은 단위 테스트의 HttpClient 대역, 비동기 주소 처리는 ReverseGeocoder 대역을 사용해
  실제 카카오로 좌표를 전송하지 않았다. 큐 포화·작업 실패도 최초 저장 응답에 예외를 전파하지 않는다.
- 실제 공유 예측표와 실제 카카오 키·네트워크는 미검증이다. 앞선 격자 샘플 검증은 유지하며
  공유 버전의 top_percent 약 0.00을 실측값으로 확정하지 않는다.
- `git diff --check` 통과. 기존 V1~V5·DBML 변경 없음, 개발·운영·공유 DB/기존 볼륨 접속 없음.
  커밋·푸시·PR 생성 없음.

### 최종 리뷰 3건 수정 검증 (2026-11-06)

- 일반 테스트 85개, 격리 PostgreSQL/PostGIS 테스트 26개 통과. 실패·오류·skip 0.
  일반 테스트·bootJar·통합 테스트 컴파일을 확인했다.
  DB 보고서: `build/parking-event-verification/parking-parking-event-2747f3db71/`.
- 숫자: 1/1.0/1e0과 양·음의 0 표현의 해시 동등성, 7자리 HALF_UP 경계,
  JSON accuracyM=1e-1000000 및 BigDecimal 최대 scale의 처리를 검증했다.
  정규화 숫자 표현이 32자 미만이며 관련 입력·해시 처리가 3초 제한 안에 끝나는지 확인한다.
  거대한 지수를 문자열이나 10의 거듭제곱으로 펼치지 않으며 실제 OOM은 유발하지 않았다.
  기존 파서/BigDecimal 범위 밖 지수의 파싱 실패도 검증했다.
- 경쟁: B가 최초 기존 이벤트 없음 조회를 마친 바로 뒤 latch에서 정지하도록 했다.
  A의 같은 키 저장·커밋 → 사용자 SHARE/차량 변경 순서의 교체·커밋 → B 재개를 강제한다.
  같은 본문은 기존 결과, 다른 본문은 IDEMPOTENCY_KEY_REUSED를 반환하고, B가 이벤트 잠금을
  정확히 한 번 거쳤는지 확인했다. 이벤트 1개·시도 횟수 1을 유지한다. 이벤트가 없는 차량 요청은 404다.
- 시각: 02:59:59.999999999/03:00:00, 토요일→일요일 자정, 22시 시작·7시 종료 경계를
  실제 DB에서 저장·재조회했다. detected_at·confirmed_at·last_known_at의 마이크로초 절삭,
  저장된 detected_at 기반의 요일·시간대·야간·expiresAt 재현, 다른 시간대/정밀도 재요청의 동일 결과를 확인했다.
  미래 허용과 시간 순서·마지막 위치 나이의 검증도 절삭된 값 기준으로 테스트했다.
- 실제 공유 데이터·카카오 키/외부 호출·계정 담당 코드와의 통합 미검증 상태는 유지한다.
  기존 Flyway·DBML을 수정하지 않았고 개발/공유 DB·기존 볼륨을 사용하지 않았다.

### #22 위치 동의 설정 정합성 검증 (2026-11-06)

- `test compilePostgresTestJava --no-daemon`: 성공. 일반 테스트 90개, 실패·오류·skip 0.
  AgreementPolicy 단위·YAML 바인딩 테스트 5개를 포함한다. 가상 환경변수만 사용하는 별도 Spring
  컨텍스트에서 AUTH_LOCATION_VERSIONS → 실제 application.yaml → Policy 전달과 빈 기본값을 확인했다.
- 기존 격리 실행기: PostgreSQL/PostGIS 통합 테스트 31개, 실패·오류·skip 0.
  보고서: `build/parking-event-verification/parking-parking-event-a01ed495a9/`.
- 새 동의 테스트 5개는 최신 현재 버전 AGREE 통과, 최신 이전 버전 AGREE 거부,
  최신 WITHDRAW의 과거 AGREE 복귀 금지, 빈 설정 거부, 버전 교체 뒤 재동의 전 403 및
  재동의 후 기존 이벤트·판정·알림·시도 횟수 유지 여부를 실제 API 역할 연결로 검증한다.
- 첫 DB 실행은 테스트용 이전 버전 이름이 기존 varchar(20)을 넘어 실패했다.
  fixture 이름만 제약 범위 안으로 수정한 뒤 전부 통과했다. 스키마를 변경하거나 오류를 무시하지 않았다.
- main/test/postgresTest/docs/scripts/application YAML/.env.example 범위의 이전 설정 키 참조는 rg 결과 0건이다.
  실제 .env는 읽거나 수정하지 않았고 새 합의 버전값을 임의로 지정하지 않았다.
- 주차 트랜잭션·사용자 잠금·최신 이벤트 조회·위험/알림 정책·좌표/시각 정규화 및 OpenApiTests.java는 변경하지 않았다.
  #22는 원격 head의 코드를 읽기만 했으며 병합·수정하지 않았다. 통합은 후속 dev 반영 시 재확인한다.

### 비차단 리뷰: 설정 진단·안내 검증 (2026-11-06)

- 일반 테스트 94개와 PostgreSQL 테스트 컴파일 통과. 로그 비노출 검사를 강화한 후
  HTTP·설정 진단 관련 테스트 10개를 추가 재실행해 통과했다. 실패·오류·skip 0.
- 고정 진단 코드 3개의 실제 검증 분기, API의 기존 INTERNAL_SERVER_ERROR 및
  예상 판정 장애의 FAILED/success=true 유지 여부를 확인했다. 루트 로그 이벤트의 메시지와
  예외 스택 모두에서 민감정보 모사 원인, 개별 요청 좌표, 원문 토큰·Bearer 헤더가 제외되는지 검사했다.
  진단 이벤트 자체에는 원인 체인/스택을 붙이지 않는다.
- 격리 PostgreSQL/PostGIS 테스트 32개 통과, 실패·오류·skip 0.
  보고서: `build/parking-event-verification/parking-parking-event-dc3f62b794/`.
  공개 묶음 미존재·미공개 및 시설 규칙 불일치에서 이벤트·이용 로그가 롤백되는 것을 실제 DB로 확인했다.
  미동결 활성 모델은 기존 CHECK가 방어하므로 조회 대역으로 검증하고 DB 제약을 우회하지 않았다.
  당시(v0.10 보완 전) 모델 2단계의 reference_fine_amount가 null인 것도 실제 DB 결과로 확인했다.
- 기동 검증·프로필·기존 SQL·DBML·판정 정책은 변경하지 않았다.
  개발/운영 DB·기존 볼륨·덤프 복원·#22 병합·커밋·푸시는 수행하지 않았다.

### API v0.10 · rules_v1 보완 검증 (2026-11-06)

- 일반 테스트 98개 및 postgresTest 컴파일 통과. 기존 격리 실행기
  `Verify-ParkingEvents.ps1`으로 PostgreSQL/PostGIS 테스트 35개 통과, 실패·오류·skip 0.
  OTHER 누락·중복·형상/거리/금액 오류, OTHER 시설의 1단계 제외, 임의 기본값 없는 금액 조회,
  규칙 변경 후 저장 금액·알림·시각 유지와 과거 null 금액 미보정, HTTP/Swagger·로그 비노출 회귀를 포함한다.
  보고서: `build/parking-event-verification/parking-parking-event-9438413ffa/`.
- 별도 신규 tmpfs PostGIS DB에 bootstrap과 실제 Flyway V1~V5를 적용하고 민지님 덤프의
  참조 16개 테이블만 재복원했다. 사용자·차량·동의·주차 기록은 가져오지 않았다.
  보호 트리거를 끄지 않고 미동결 부모 → 자식 → 원본 동결 상태 검증/복원 → 활성화 순서로 적재했다.
  소유권·ACL·함수·보호 트리거의 적재 전후 fingerprint가 같고 참조 행 수가 원본과 일치했다.
- 실제 zone_types 3개와 기존 동일 묶음 0개를 확인하고 첨부 SQL을 적용했다.
  미공개 3행의 형상·거리·금액을 확인한 뒤 **이 임시 DB에서만** 공개했다.
  재실행은 중복 키로 실패하며 부분 추가가 없고, 공개 후 UPDATE는 기존 트리거가 거부한다.
  `docs/rules_v1.sql`과 `docs/schema/rules_v1.sql`은 SHA-256이 동일하며 원문은 수정하지 않았다. 규칙 SQL은 `docs/rules_v1.sql` 하나로 관리한다.
- 현재 코드의 실제 컨트롤러·서비스·트랜잭션 경로에 `yongin-2026-11-v1` 설정을 연결했다.
  시설 우선순위를 추측하지 않고 서로 다른 시설 유형이 겹치지 않는 실제 시설 점을 선택했다.
  소화전·정류소 모두 SUCCEEDED/1/SKIPPED와 시설 규칙의 금액·대표 근거를 커밋했다.
- 시설 규칙 미해당 샘플은 실제 활성 `lgbm-yongin-v1`으로 SUCCEEDED/2/OK,
  primaryZoneType=null, MODEL 대표 근거 및 OTHER 금액 40000 저장·반환을 확인했다.
  금액은 SQL의 실제 값이며 Java 상수는 아니다. 같은 테스트는 별도 fixture 금액 47000/52345로도 검증한다.
  설정을 꺼도 원시 top_percent의 상위 2% 예외로 SEND다. expiresAt은 저장된 detected_at + 30분이다.
  차량·알림 설정·규칙 선택과 서버 시각 변경 뒤 재요청은 200이며 원래 금액·문구·판정 시각·알림 응답과 동일하다.
  대표 근거 삭제는 커밋 시 거부되고 기존 근거가 유지된다.
- 실제 데이터 시험의 사용자·동의·차량은 해당 임시 DB의 명시적 fixture뿐이며 시험 후 삭제했다.
  HTTP 인증·응답은 별도 격리 회귀 테스트로 검증했다. 실제 카카오 호출이나 공유 DB 직접 접속은 하지 않았다.
  계정 담당 코드 통합·법령 금액 검증·문구 최종 확정은 미검증/별도 확인으로 남긴다.
  개발 DB·기존 볼륨·기존 Flyway·DBML 변경, 커밋·푸시는 하지 않았다.
