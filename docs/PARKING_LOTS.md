# 주변 주차장 적재 및 조회

기준: `docs/reference/용인 불법주정차 알림 — API 명세 v0.10 (ERD v5.1 기준).docx`
5절, 2절의 위치 동의, 8절 #6·#13 및 시간 표기 결정. 기존 V2/V5와 ETL의 파일 해시·활성 원천 계약을 사용한다.

## CSV와 적재

실제 입력 `data/raw/경기도_용인시_주차장정보.csv`는 UTF-8 BOM 포함, 214행, 32컬럼이다.
관리번호 중복은 7쌍(추가 행 7건), 관리번호+이름 중복은 0건이다.
좌표가 없는 기흥역환승(CSV 88행), 풍덕천2공영(94행)은 제외하여 212건을 적재한다.
중복 관리번호 7쌍 중 한 행이 이 스킵 대상이므로 적재 후 관리번호 중복은 6쌍이다.
모든 원천 행의 구분은 공영이고, 요금정보는 무료 165행/유료 49행이다(스킵 전).
원천은 기존 `.gitignore`의 `data/raw/`로 제외된다.

```powershell
python etl/load_parking_lots.py --input 'data/raw/경기도_용인시_주차장정보.csv'
```

접속은 기존 `etl/db.py`의 `connect()`와 적재 계정(`parking_loader`)을 사용한다.
기존 ETL Python 파일과 마이그레이션은 수정하지 않는다. 기존 requirements의 psycopg/pandas/pytest를 사용하며 새 의존성은 없다.
일반 실행은 기존 `etl/.env` 설정을 읽지만 이번 검증은 전용 DB 연결을 주입하며 실제 `.env`를 읽거나 수정하지 않는다.

- `source_dataset=yongin_parking_lots`, 파일 전체 SHA-256, `관리번호|이름` 키를 사용한다.
  복합 키 중복은 전체 적재를 거부한다. 좌표 결측 행도 키 중복 검사에서 제외하지 않는다.
  같은 파일은 기존 import와 행을 재사용한다. 데이터셋 advisory lock으로 동시 적재/활성화를 직렬화한다.
- 기존 ETL의 활성 원천 방식에 따라 변경된 파일은 새 스냅샷으로 적재하고 이전 import를 비활성화한다.
  과거 행과 ID는 유지하며 API는 활성 스냅샷만 조회한다. 이전 파일을 재실행해도 최신 활성 스냅샷을 되돌리지 않는다.
  원천 행을 다른 파일 사이에서 병합·덮어쓰거나 관리번호만으로 합치지 않는다.
  **별도 결정 사항:** 행 단위 최신화/삭제 및 과거 스냅샷 보존 기간은 정하지 않았다. 현재는 기존의 파일 단위 활성화 계약만 사용한다.
- import 생성, 행 INSERT, 이전 원천 비활성화, 새 원천 활성화가 하나의 트랜잭션이다.
  실패하면 전부 롤백한다. 불완전한 기존 import는 자동 덮어쓰기하지 않고 오류로 중단한다.
- 좌표 결측은 스킵하고 행 번호·사유를 경고 및 import note에 남긴다. 잘못된 숫자/범위는 오류로 중단한다.
- 요금정보 무료→FREE, 유료→PAID, 혼합→MIXED. 미상/빈 값은 NULL과 사유 기록이다.
  설명·요금 숫자로 요금 구분을 추정하지 않는다. fee_info에는 요금 관련 컬럼과 특기사항을 이름과 원문 값으로 보존한다.
- 구분은 공영→PUBLIC, 민영→PRIVATE, 부설→ATTACHED이며 부설을 제외하지 않는다. 미상은 NULL과 사유 기록이다.
- 시간 6컬럼을 개별 요일의 쌍으로 읽는다. 결측 쌍은 NULL, 읽을 수 없거나 한쪽만 있는 쌍은 둘 다 NULL과 원문·사유 기록이다.
  24시간은 원천 그대로 00:00~23:59, 종료가 빠르면 익일까지 운영한다. 특기사항으로 시간을 바꾸지 않는다.
  실제 파일은 모든 시간 쌍이 00:00~23:59이며 면수와 기준일은 원천 값을 저장한다.

## GET /api/v1/parking-lots/nearby

인증 필수. `lat`, `lng` WGS84 필수(-90~90, -180~180; NaN/무한대 불가),
`radius`는 선택 정수 미터(기본 500, 양수, 최대 1000)이다. 위반하면 400 INVALID_INPUT.
최신 명세는 WGS84 범위만 정하며 용인 행정경계 제한은 명시하지 않는다. 행정경계 제한·limit를 추가하지 않았다.
500m 요청 결과가 비었을 때만 1000m로 확장한다. 다른 반경에는 확장 정책을 추가하지 않는다.

기존 `ParkingStore.lockUserAndConsent`로 사용자 FOR SHARE와 최신 LOCATION_SERVICE 동의/현재 허용 버전을 확인한다.
동의 없으면 403 LOCATION_CONSENT_REQUIRED. 기존 `logUse`를 재사용해 USE를 한 번 기록한다.
목적은 `주변 주차장 조회`, 수집 방법은 `요청 좌표`, parking_event_id는 NULL이다. 좌표 자체는 로그 테이블에 저장하지 않는다.
이용 로그 INSERT 실패도 요청 전체를 실패시킨다. 조회와 기록은 한 트랜잭션이며 REPEATABLE READ로 확장 시에도 같은 원천/공휴일 스냅샷을 유지한다.

ST_DWithin(geography)의 GiST 필터와 ST_Distance 직선 거리 오름차순을 사용한다(동거리 id순).
응답 필드는 명세 그대로 radiusM, expanded, items이고, 각 항목은
id, name, address, distanceM, location{lat,lng}, parkingType, feeType, feeInfo, operatingHours, capacity이다.
원천 없는 값은 명시적 null이다. 결과가 없어도 200, items: []이며 확장했다면 radiusM=1000, expanded=true다.
KST 조회일 공휴일 우선→일요일 공휴일 칸→토요일 칸→평일 칸을 골라 HH:mm-HH:mm 한 개를 반환한다.
일요일 공휴일 칸 사용은 명세 #13의 임시 규칙이며, NULL을 휴무로 해석하지 않는다.

Swagger에는 요청 범위, 반경 확장 조건, 응답 필드·nullable, 400/401/403/500을 반영한다.
일반 DEBUG/오류 로그에는 요청 좌표·예외 메시지/스택을 출력하지 않는 전용 advice를 사용한다.
기존 path-only Tomcat 접근 로그 설정을 유지하며 JDBC bind TRACE는 INFO로 고정한다.
위치 이용 테이블의 의도된 USE 감사 기록과 일반 애플리케이션 로그는 별개다.

## 검증 실행

```powershell
python -m pytest etl/tests/test_load_parking_lots.py
./scripts/db/Verify-ParkingLotsEtl.ps1 -Python 'build/parking-lots-venv/Scripts/python.exe'
./scripts/db/Verify-ParkingLotsApi.ps1 -JavaHome 'C:/Users/dlswn/.jdks/ms-21.0.13.0.2'
./gradlew.bat test build compilePostgresTestJava --no-daemon
git diff --check
```

검증용 스크립트를 추가한 이유는 개발 DB나 `.env`를 사용하지 않고 재실행·롤백·실제 역할 권한을 재현하기 위해서다.
두 스크립트는 매번 새 tmpfs PostGIS 컨테이너를 만들고 종료한다. 기존 볼륨은 사용하지 않는다.
ETL 검증은 현재 디렉터리의 전체 V*.sql을 숫자 순서로 적용하며, API는 classpath 전체 Flyway를 적용한다.
최신 버전을 V6로 고정하지 않는다. 검증 시점 저장소에는 V1~V6가 있다.
ETL 테스트는 parking_loader, API 테스트는 parking_api이며 마이그레이션만 parking_migrator를 사용한다.
실제 CSV 2회 적재에서 import 수·주차장 ID·키가 동일한지 검사하며 DB 실패 후 잔여 import/부분 행도 검사한다.
API는 정렬/확장/빈 결과/명시적 반경/활성 원천/시간·공휴일/동의/USE/로그 실패/인덱스/사용자 잠금을 검사한다.
HTTP 테스트는 인증·입력 오류·명시적 null·Swagger와 실제 DEBUG/오류/접근 로그의 좌표 비노출을 검증한다.

2026-11-08 검증 결과: ETL 14건, 주변 주차장 postgresTest 8건, 기존 주차 이벤트 postgresTest 42건,
일반 테스트 131건 모두 실패·스킵 없이 통과했다. `test build compilePostgresTestJava`와 JAR 생성,
`git diff --check`도 통과했다. ETL 실제 CSV는 214행 중 212행 적재·2행 스킵이며,
같은 파일 2회 실행 후 import 수와 주차장 ID/키가 동일했다.
격리 DB만 사용했고 작업 브랜치 `feat/29-parking-lots-etl`에서 커밋·푸시는 수행하지 않았다.

## 커밋 전 추가 리뷰 (2026-11-08)

기존 일반 테스트 131건, 개인정보 로그 HTTP 테스트 및 주차 이벤트 회귀 42건의 결과를 재사용했다.
추가 테스트는 실제 parking_api SQL과 트랜잭션 프록시를 연결한 MVC 테스트로 성공 USE 1건,
감사 행 전체의 좌표 미저장, 미동의 403과 추가 로그 없음, 확장/빈 결과 200,
범위 밖 입력 400과 추가 로그 없음, 주말/공휴일 시간 결측의 명시적 null을 확인했다.
JWT 필터는 기존 HTTP 테스트가 담당하며 새 DB+MVC 테스트는 인증된 principal을 직접 주입한다.
500m/1000m 조회 사이 활성 import가 바뀌어도 한 스냅샷을 유지하고 다음 요청부터 새 원천을 사용하는 것도 통과했다.
ETL은 활성화 마지막 UPDATE를 DB 트리거로 실패시켜 이전 활성 상태와 모든 import/주차장 행 복원을 확인했다.
ETL은 15건 모두 통과했다.

**발견된 결함:** `ParkingLotService.nearby`의 REPEATABLE READ는 사용자 잠금 대기 이전 스냅샷을 사용한다.
철회 트랜잭션이 먼저 사용자 FOR UPDATE를 잡고 WITHDRAW 이벤트를 INSERT한 뒤,
조회가 FOR SHARE에서 기다리는 것을 확인하고 철회를 COMMIT하면 조회는 과거 AGREE를 읽고 성공한다.
실제 재현에서 최신 이벤트는 WITHDRAW인데 USE 1건이 추가됐다.
`withdrawalCommittedWhileLookupWaitsMustPreventUse` 회귀 테스트는 403 동의 오류/USE 0건을 요구하며 현재 실패한다.
따라서 최신 주변 주차장 postgresTest 결과는 **12건 중 11건 통과, 1건 실패**다.
위의 최초 구현 검증 결과와 구분해야 한다. 재현은 `Verify-ParkingLotsApi.ps1`로 가능하다.
공개 LOCATION_SERVICE 철회 API는 현재 명세 #4에 따라 503으로 차단되므로 공개 경로의 철회 성공을 재현한 것은 아니다.
하지만 명세가 요구한 잠금 계약에는 맞지 않는다. 동의 읽기의 최신성과 원천 조회의 일관성을 각각 보장하도록 수정해야 한다.
단순 READ COMMITTED 전환만 하면 확장 중 원천이 섞일 수 있으므로, 예를 들어 잠금 후 최신 동의를 읽고 활성 import ID를 고정해 두 조회에 재사용하는 방식을 검토한다.
리뷰에서는 구현을 수정하지 않았으며 실패 회귀 테스트를 유지했다.

비차단 결정 사항: 현재 변경 파일은 전체 스냅샷을 활성화하며, 모든 행이 좌표 결측인 파일도
0건 스냅샷으로 활성화할 수 있다. 이를 허용할지, 최소 유효 행 조건을 둘지와 과거 스냅샷 보존 기간은 별도 정책으로 정한다.
