# 주차장 ERD v5.1 적용 — Flyway V5

## 기준과 기존 상태

작업 브랜치는 `feat/7-db-flyway`다. 기존 DB/Flyway 작업과 데이터를 보존했다.
V4는 `V4__erd_v5_grants.sql`에서 사용하며, 이전 격리 검증 로그에도 V1~V4 성공 이력이 있다.
개발 DB의 현재 적용 이력은 실행 전에 별도 확인한다. 아래 검증은 격리 DB에서 수행하며,
개발 DB에 자동 적용하지 않는다.

현재 기준 파일은 다음과 같다.

- `src/main/resources/db/migration/`의 V1~V5 적용 SQL
- `docs/schema/yongin_parking_erd_v5.1.dbml`
- `docs/reference/용인 불법주정차 알림 — API 명세 v0.6 ERD v5.1 기준.docx`

주차장 적용 파일은 `src/main/resources/db/migration/V5__parking_lots_hours_and_fee.sql`이다.
V4는 권한 설정에 사용되므로 주차장 변경은 V5를 사용한다.
중복 참고 SQL·DBML은 정리했고 기존 V1~V5의 내용은 변경하지 않았다.

## 변경과 SQL / DBML 일치

| 항목 | V5 SQL / 프로젝트 DBML |
| --- | --- |
| `fee_type` | `varchar(10)`, nullable, 기본값 없음; FREE / PAID / MIXED / NULL |
| `weekday_open`, `weekday_close` | `time` (= time without time zone), nullable, 기본값 없음 |
| `saturday_open`, `saturday_close` | 동일 |
| `holiday_open`, `holiday_close` | 동일 |
| 제거 | `is_paid`, `operating_hours` |
| `ck_pl_fee_type` | `fee_type IS NULL OR fee_type IN ('FREE','PAID','MIXED')` |
| `ck_pl_weekday_pair` | `(weekday_open IS NULL) = (weekday_close IS NULL)` |
| `ck_pl_saturday_pair` | `(saturday_open IS NULL) = (saturday_close IS NULL)` |
| `ck_pl_holiday_pair` | `(holiday_open IS NULL) = (holiday_close IS NULL)` |

`docs/schema/yongin_parking_erd_v5.1.dbml`은 요금 CHECK와 시간 쌍 CHECK 3개를 모두 포함한다.
일요일 공용은 임시 정책으로 명시했다. DBML로 전체 스키마를 재생성하지 않으며,
변경 전 스키마는 기존 V1~V4 마이그레이션으로 보존한다.

V5는 DDL/이관/CHECK를 한 파일에서 실행한다. 데이터 검사 전
`LOCK TABLE public.parking_lots IN ACCESS EXCLUSIVE MODE`를 추가했다.
검사와 컬럼 삭제 사이에 다른 세션이 운영시간을 기록하는 경쟁을 막는다.
이 잠금은 읽기/쓰기와 충돌하므로 실제 적용은 짧은 유지보수 구간에 수행한다.
전체 파일은 PostgreSQL의 Flyway 단일 트랜잭션에서 실행된다. 자동 커밋 psql로 문장별 실행하지 않는다.
`CASCADE`, `clean`, baseline, 기존 데이터 초기화는 사용하지 않는다.

기존 PK, import FK, `(import_id, source_record_key)` 유니크 인덱스,
`ix_parking_lots_location` GiST 인덱스와 테이블 소유권/역할별 권한은 유지한다.
V4 기준 별도 컬럼 ACL이나 제거 컬럼 의존 객체는 없음을 격리 DB에서 확인했다.
API는 SELECT, ETL은 기존 DML 권한을 유지한다. DROP은 기본 RESTRICT이므로 추가된 외부 의존 객체가
있으면 강제로 삭제하지 않고 실패한다. 현장 DB가 다르면 적용 전에 뷰/함수/컬럼 권한을 따로 확인한다.

## 기존 데이터 이관과 중단 시 대응

`is_paid=true → PAID`, `false → FREE`, `NULL → NULL`이다.
기존 운영시간이 모두 NULL일 때만 진행하며 새 시간 6컬럼은 NULL로 둔다.
기존 요금 설명, 주소, 위치, 수용 대수, 원천 키 등 다른 데이터는 그대로 둔다.

`operating_hours IS NOT NULL`인 행이 하나라도 있으면 오류로 중단한다.
빈 문자열도 데이터로 간주한다. 요금 이관/컬럼 추가/삭제/제약 변경과 V5 성공 이력은 남지 않는다.
다음 SQL은 적용 전 읽기 전용 점검용이다.

```sql
SELECT count(*) AS legacy_hours_rows
FROM public.parking_lots WHERE operating_hours IS NOT NULL;
```

중단되면 자동으로 NULL 처리하거나 기존 컬럼을 먼저 삭제하지 않는다. 대응 순서는 다음과 같다.

1. 관리자 또는 승인된 ETL 계정으로 전체 테이블/DB 백업과 원문 내보내기를 수행한다.
   psql의 아래 명령은 psql 실행 머신에 파일을 만든다. 컨테이너 안에서 실행하면
   컨테이너 밖 안전한 위치로 별도 복사한다.

   ```text
   \copy (SELECT id,import_id,source_record_key,operating_hours FROM public.parking_lots WHERE operating_hours IS NOT NULL ORDER BY id) TO 'parking_lots_legacy_hours.csv' WITH (FORMAT csv, HEADER true)
   ```

2. ID와 import/원천 키를 유지하는 별도 보존 테이블 또는 보안 저장소에 원문을 보존하고,
   백업 행 수·원문·복구 가능성을 대조한다. CSV 한 번 생성만으로 이전 완료라고 판단하지 않는다.
3. 사람이 검토한 매핑/원천 설명서를 기준으로 외부 staging에서 시간 쌍을 정리한다.
   새 컬럼을 미리 같은 이름으로 추가하면 V5의 ADD COLUMN과 충돌하므로 이 마이그레이션 전에
   임의로 컬럼을 추가하지 않는다. 수동 이전 또는 새 import 재적재 방식을 결정한다.
4. 백업과 이전 계획이 승인된 별도 유지보수 절차에서만 구 컬럼을 비우는 작업을 수행한 뒤
   변경하지 않은 V5를 다시 실행하고, 보존된 staging/원천에서 새 시간 쌍을 적재·검증한다.
   이 절차는 이번 코드가 자동 실행하지 않는다. 읽을 수 없는 원문·특기사항도 보존한다.

## 후속 API / 적재 계약

이번에는 주변 주차장 API·적재 파이프라인·AI 학습을 구현하지 않았다.

- API 필드는 `isPaid → feeType`; FREE / PAID / MIXED / null이다. 요금 설명은 `feeInfo`에 둔다.
- API `operatingHours` 이름은 유지한다. DB의 삭제된 `operating_hours` 문자열 컬럼과 별개로,
  조회일을 **Asia/Seoul**로 계산해 하루의 시간 한 쌍을 표현한다.
- 공휴일이면 공휴일 칸, 공휴일 아닌 토요일이면 토요일 칸, 월~금이면 평일 칸을 사용한다.
  일요일은 공휴일 칸을 사용하는 임시 정책이며 원천 정의 확인 후 확정한다.
- 값이 없으면 null이다. 휴무라고 단정하지 않는다. 시작/종료 하나만 저장할 수 없다.
- 00:00~23:59는 24시간 표시용 관례다. 종료가 시작보다 빠르면 다음 날 종료로 해석한다.
  DB에는 종료 >= 시작 제약을 넣지 않았다. 시작=종료도 저장 가능하나 의미를 임의로 해석하지 않는다.
- 읽을 수 없는 원천 시간은 해당 쌍을 모두 NULL로 하고 적재 로그에 원인/원천 키를 남기는 방향이다.
  원문은 별도로 보존한다.

API v0.6의 “기존 isPaid, operatingHours는 DB에서 없어짐 / V4” 문구는 DB 컬럼 변경 설명이다.
API 응답의 `operatingHours` 제거를 뜻하지 않으며 프로젝트 실제 버전은 V5다.
참고 API 원본은 수정하지 않고 이 차이를 여기 기록했다.

공유 분석의 214건 모두 00:00~23:59라는 관측은 원천 기본값일 가능성이 있다.
이 값만으로 실제 24시간 운영을 검증했다고 설명하지 않는다.
관리번호 중복 7건의 병합/구분, 위경도 결측 2건의 보완/제외,
특기사항의 시간 구조화 기준은 미결이며 이번 마이그레이션은 처리하지 않는다.
특기사항 원문을 버리는 처리도 추가하지 않았다.

## 검증

재현 명령은 기존 [DB 가이드](DATABASE.md)의 Java 21/Docker 준비 후 다음과 같다.

```powershell
./gradlew.bat test bootJar --no-daemon
./scripts/db/Verify-Database.ps1
```

스크립트는 새 tmpfs 컨테이너의 서로 다른 DB에서 빈 DB 전체 적용,
V4에서 true/false/NULL fixture를 넣은 후 V5 적용, 기존 문자열이 있는 V4에서 V5 중단을 검증한다.
기존 테스트·회귀 SQL·동시성 테스트·local HTTP 기동 검증도 유지한다.
기존 PK/FK/인덱스는 정의뿐 아니라 객체 OID까지 비교하고 테이블 ACL/원래 행도 대조한다.
문자열 및 빈 문자열 중단 시에는 전체 행·컬럼 메타데이터·제약·인덱스·ACL·이력을 대조한다.

2026-11-03 실제 실행 결과:

| 검증 | 결과 |
| --- | --- |
| 환경 | PostgreSQL 16.15 / PostGIS 3.6.4, Java 21, SCRAM 계정 인증 |
| 기존 테스트 / 빌드 | 35개 통과, 실패 0, bootJar 성공 |
| 빈 DB | V1~V5 전체 적용 성공 |
| 기존 V4 DB | true/false/NULL → PAID/FREE/NULL, 새 시간 6컬럼 NULL, 기존 나머지 데이터 보존 |
| 재실행 | 빈 DB 적용 후와 V4→V5 적용 후 모두 추가 적용 없음 |
| 요금 CHECK | FREE/PAID/MIXED/NULL 허용, INVALID 거부 |
| 시간 CHECK | 각 요일 시작만/종료만인 6가지 거부; NULL 쌍·정상·자정 넘김·같은 시각·00:00~23:59 허용 |
| 원문 보호 | 문자열과 빈 문자열 각각 V5 중단, V4 데이터·컬럼·제약·인덱스·ACL·이력 동일 |
| 보존 | PK/FK/유니크/GiST 정의와 OID 및 테이블 권한 동일; 컬럼 권한/기존 의존성 검사 통과 |
| 기존 DB 회귀 | 제공 SQL 36 PASS / 0 FAIL, 동시성 C1/C2 PASS |
| 기동 | local 프로필 API 계정의 JPA 초기화 및 HTTP health 응답 성공 |

상세 로그: `build/db-verification/parking-flyway-verify-787cb9bf41/` (Git 제외).
해당 스크립트가 시작한 JVM/컨테이너는 검증 후 정지했다. 개발 DB·볼륨은 변경하지 않았다.
실제 IDE 조작과 개발 DB 마이그레이션은 실행하지 않았다. 엔티티가 없어 전체 JPA 매핑 검증도
수행한 것으로 보지 않는다. 실제 214건 재적재/시간 파싱과 API 응답 구현은 후속 작업이다.
