# 이슈 #17: 주차 저장·위험 판정 API

`POST /api/v1/parking-events`의 인증·저장·판정·알림 응답과 커밋 후 비동기 주소 갱신을 구현했다.
신규는 201, 기존 이벤트 처리(FAILED 재판정 포함)는 200이다. 기존 ApiResponse를 사용한다.
실제 공유 예측표 접속은 미확인이다. 조회·delivery·LLM·계정·ETL API는 추가하지 않는다.

## 확인한 기준

- `docs/reference/`의 DOCX는 파일명과 본문 첫 제목 모두 API v0.8 / ERD v5.1이다.
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
과태료는 실제 대표 zone_rule의 `fine_amount_car`만 사용한다. 모델에 대응하는 과태료 출처가 없어
2·3단계에 예시의 40000을 만들어 넣지 않는다.

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

## 알림 결정과 문구

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
