# PostgreSQL / Flyway — ERD v5 (#7)

현재 주차장 스키마는 V5로 ERD v5.1에 맞췄다. 변경 내용·원문 보호·API v0.6 정합성과
검증 결과는 [주차장 V5 가이드](PARKING_LOTS_V5.md)를 참고한다.

## 범위와 기준

Java 21, Spring Boot 4.1.2, Gradle 9.8.2를 유지한다. Boot가 관리하는 JPA,
PostgreSQL JDBC, Flyway starter 및 PostgreSQL 지원 모듈을 사용한다.
실제 해석된 버전은 Hibernate 7.4.13.Final, Flyway 12.4.0, JDBC 42.7.14다.
Boot 4에서는 Flyway 자동 구성을 위해 starter가 필요하다
([공식 모듈화 안내](https://spring.io/blog/2025/10/28/modularizing-spring-boot/)).

기준은 `docs/reference/schema_v5.sql`, `migration_extra.sql`,
`roles_and_grants.sql`, API v0.5 DOCX다. 요청의 `migration_extra_v3.sql` 대신
제공된 `migration_extra.sql`을 사용하도록 사용자 확인을 받았다. 참고 원본은 수정하지 않았다.

엔티티·BaseEntity·Auditing·컨트롤러·인증·판정 로직은 추가하지 않는다.
Hibernate Spatial/JTS는 엔티티 공간 타입 매핑을 구현하는 후속 작업에서 추가한다.
현재 SQL 마이그레이션은 JDBC/PostGIS만으로 실행되므로 Spatial 의존성이 필요하지 않다.
후속 매핑 시 Boot가 관리하는 Hibernate와 같은 버전의 `org.hibernate.orm:hibernate-spatial`을 사용하고,
geometry/geography 및 SRID 매핑을 PostgreSQL에서 별도 검증한다.

## 계정과 책임

| 계정/역할 | 책임 |
| --- | --- |
| `.env`의 `POSTGRES_USER` | Compose 초기 DB 관리자. 확장 설치·역할 생성·멤버십·로그인 비밀번호 설정에만 사용 |
| `parking_migrator` | Flyway 로그인. 일반 계정이며 앱 테이블·함수·이력의 소유자. public에 CREATE, DB에 CREATE(이력 스키마 생성용) 권한 보유 |
| `parking_api` → `parking_app` | API 런타임. 사용자 데이터 DML 및 기준 SQL에서 허용한 참조 데이터 조회 |
| `parking_loader` → `parking_etl` | 적재·동결·활성화. 동결 데이터 변경과 purge는 허용하지 않음 |
| `parking_operator` → `parking_admin` | ETL 권한과 purge 실행 권한 |

그룹 역할은 NOLOGIN이며 런타임/적재/관리 로그인은 슈퍼유저·CREATEROLE이 아니다.
앱·ETL·admin에 migrator 역할을 부여하지 않는다. purge는 migrator 소유의
SECURITY DEFINER 함수이며, 실행자는 admin만 허용한다. GUC 값만 위조해서는
삭제 가드를 통과할 수 없다. public 스키마의 PUBLIC CREATE도 회수한다.
DB 소유자·슈퍼유저는 트리거를 우회할 수 있으므로 API 실행에 사용하지 않는다.
기존 동일 이름 역할에 위험한 속성이나 소유자 멤버십이 있으면 bootstrap은 실패한다.
기존 역할의 다른 멤버십·소유권도 관리자가 확인해야 한다.

로컬에서는 IDE JVM 안에서 별도 Flyway 연결(소유자)과 Hikari 연결(API)을 사용한다.
따라서 로컬 JVM에는 두 계정 자격증명이 필요하다. 배포 환경에서는 마이그레이션을
별도 작업으로 실행하고 런타임에는 API 자격증명만 전달하는 구성을 후속으로 마련한다.
이번 변경은 local 프로필을 대상으로 하며 prod 연결·배포 절차는 추가하지 않는다.

## 팀원 최초 실행

1. Java 21을 선택한다. `.env`가 없다면 `.env.example`을 복사한다. 기존 `.env`는 덮어쓰지 않는다.
   `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`를 채우고,
   `DB_APP_PASSWORD`, `DB_MIGRATION_PASSWORD`도 서로 다른 로컬 비밀번호로 설정한다.
   DB 로그인 이름은 부트스트랩 기본값(`parking_api`, `parking_migrator`)을 사용한다.
   `.env`는 Git에서 제외된다. `.env.example`의 비밀번호는 비어 있다.
2. 저장소 루트 PowerShell에서 실행한다.

   ```powershell
   . ./scripts/Import-LocalEnv.ps1
   docker compose up -d db
   docker compose cp ./scripts/db/bootstrap.sql db:/tmp/parking-bootstrap.sql
   docker compose exec db psql -X -U $env:POSTGRES_USER -d $env:POSTGRES_DB -f /tmp/parking-bootstrap.sql
   docker compose exec db psql -X -U $env:POSTGRES_USER -d $env:POSTGRES_DB
   ```

3. 마지막 명령의 psql 안에서 비밀번호를 대화형으로 설정한다. `.env`에 적은 각 값과 맞춘다.
   비밀번호를 SQL 파일·명령 인자·채팅·로그에 붙여 넣지 않는다.

   ```text
   \password parking_migrator
   \password parking_api
   \q
   ```

   ETL/운영 계정이 필요할 때만 같은 방법으로 `parking_loader`, `parking_operator` 비밀번호를 설정한다.
   초기에는 비밀번호가 없어 비밀번호 인증으로 로그인할 수 없다.
   부트스트랩은 관리자 권한이 필요하지만 Flyway에는 슈퍼유저나 역할 생성 권한을 주지 않는다.
   기존 Compose 볼륨에서는 `.env`의 POSTGRES 비밀번호 변경만으로 DB 비밀번호가 바뀌지 않는다.

4. IDE에서 Java 21과 `ParkingServerApplication`을 선택하고 실행 환경에 다음 변수를 주입한다.

   | 변수 | 값/용도 |
   | --- | --- |
   | `SPRING_PROFILES_ACTIVE` | `local` |
   | `POSTGRES_DB` | `.env`의 DB 이름 |
   | `DB_HOST`, `DB_PORT` | `localhost`, `5433` (기본값) |
   | `DB_APP_USER`, `DB_APP_PASSWORD` | `parking_api`, 해당 비밀번호 |
   | `DB_MIGRATION_USER`, `DB_MIGRATION_PASSWORD` | `parking_migrator`, 해당 비밀번호 |

   IDE 실행 구성의 환경변수에 직접 등록하거나, 해당 IDE가 지원하는 `.env` 파일 주입을 설정한다.
   Compose가 루트 `.env`를 읽는 것은 IDE JVM의 환경변수 설정과 별개다.
   이미 켜진 IDE에는 PowerShell에서 설정한 환경변수가 소급 전달되지 않는다.
   `.env` 자체를 Spring 설정 파일로 import하지 않는다.
   터미널에서 동일하게 실행하려면 아래를 사용한다.

   ```powershell
   . ./scripts/Import-LocalEnv.ps1
   ./gradlew.bat bootRun
   ```

   로더는 현재 PowerShell 프로세스에만 설정하고 값을 출력하지 않는다. 허용 형식은
   한 줄 `KEY=value`, 빈 줄, `#` 주석, 값 전체의 작은/큰따옴표다. 셸 명령·변수 확장·
   여러 줄 값·인라인 주석은 지원하지 않는다. `$`가 들어간 값은 Compose 확장을 피하도록
   `.env`에서 작은따옴표로 감싼다. 비밀번호에 공백이나 특수문자가 있으면 IDE에도 원래 값을 전달한다.

5. 첫 실행의 Flyway 완료와 앱 기동을 확인한다. 다음 실행은 `No migration necessary`가 정상이다.
   `/api/v1/health`는 기존 HTTP 기본 응답만 확인하므로 DB 상태 검사 API로 해석하지 않는다.

Compose 프로젝트는 `yongin-parking`, 서비스는 `db`, 이미지는 `postgis/postgis:16-3.6`이다.
Spring Boot는 IDE에서 실행하고 Compose에는 DB만 실행한다. `docker compose down -v`나
개발 DB 초기화는 하지 않는다.

## 적용 순서와 변경 원칙

| 순서 | 파일 | 내용 |
| --- | --- | --- |
| 관리자 선행 작업 | `scripts/db/bootstrap.sql` | PostGIS 확장 준비 → 역할/로그인 생성 → admin의 ETL 멤버십 → 스키마/접속 권한 |
| V1 | `V1__verify_postgis.sql` | public의 PostGIS 3.6 확인 및 소유자 계정 분리 확인 |
| V2 | `V2__erd_v5_schema.sql` | schema_v5 원문: 테이블 25개, 인덱스, 주석, CHECK/FK/CASCADE |
| V3 | `V3__erd_v5_guards_and_indexes.sql` | migration_extra 원문: 부분 유니크·공간 인덱스·함수·트리거 |
| V4 | `V4__erd_v5_grants.sql` | 기준 역할별 테이블·시퀀스·함수 권한 |
| V5 | `V5__parking_lots_hours_and_fee.sql` | 요금 3값·시간 6컬럼, 원문 존재 시 중단, CHECK 4개 |

V2/V3에는 `SET LOCAL search_path = public, pg_catalog`만 앞에 추가했다.
V4의 역할 생성과 역할 간 GRANT는 관리자 bootstrap으로 이동했다. PUBLIC 권한 회수는
마이그레이션 소유 객체에 한정해 관리자 소유 PostGIS 객체를 건드리지 않는다.
기준 역할별 권한 의도는 그대로 유지한다.

Flyway 이력은 `flyway.flyway_schema_history`, 업무 테이블은 `public`이다.
PostGIS가 public을 먼저 채우더라도 별도 이력 스키마를 사용하므로 baseline으로 숨길 필요가 없다.
기존 업무 테이블이 있는데 이력이 없으면 생성 충돌로 실패한다. 이때 baseline/repair/테이블 삭제로
우회하지 말고 기존 DB와 기준 SQL의 차이를 확인한다.
기존 마이그레이션은 없어 V1부터 시작했다. 이미 성공 적용된 파일은 변경하지 않고
후속 버전을 추가한다. 실패한 PostgreSQL 트랜잭션은 롤백되며 성공 버전은 유지된다.

`ddl-auto=validate`, `spring.sql.init.mode=never`, `baseline-on-migrate=false`,
`clean-disabled=true`다. Flyway placeholder 치환을 꺼 원본 SQL의 내용을 그대로 실행한다.
Hibernate와 schema.sql은 테이블을 생성하지 않는다.

## 검증 재현

Java 21과 Docker를 준비하고 저장소 루트에서 실행한다.

```powershell
./gradlew.bat test bootJar --no-daemon
./scripts/db/Verify-Database.ps1
```

검증 스크립트는 `JAVA_HOME/bin/java.exe`를 사용한다. 필요하면 `-Java`에 Java 21 실행 파일을 전달한다.
자식 JVM은 부모의 Spring/JVM 설정을 상속하지 않으며 실제 두 연결의 DB 식별자를
마이그레이션 전에 확인한다. [연결 격리와 미끼 DB 회귀 검증](VERIFICATION_ISOLATION.md)을 참고한다.
매번 고유 이름의 새 컨테이너를 만들고, localhost 임의 포트와 tmpfs DB를 사용한다.
`.env`·Compose DB·개발 볼륨을 읽거나 변경하지 않는다. 초기 관리자 준비 후 JDBC TCP는
SCRAM 비밀번호 인증으로 바꾸고 임의 생성한 API/마이그레이션 비밀번호를 사용한다.
검증 자격증명은 보고서에 쓰지 않는다. 끝나면 자신이 시작한 JVM과 컨테이너만 정지한다.
tmpfs 데이터는 정지 시 사라지며, 정지된 검증 컨테이너 항목은 남는다.

검증 내용:

- 빈 DB에 V1~V5 전체 적용, 재실행 시 추가 마이그레이션 없음; V4→V5 이관과 원문 보호 검증 포함
- 확장, 업무 테이블 소유권, CHECK/FK, 부분 유니크 3개, 공간 인덱스 6개, 함수·트리거 8개 확인
- 앱/ETL의 purge 거부, admin의 실행 허용, 소유자 역할 가장 불가, 지연 제약 트리거 확인
- 보충 SQL: 활성 차량 1대, 이벤트 멱등키, 차량 소유권 FK, GPS 없는 AUTO 금지,
  200m 제한, 이벤트 삭제 시 로그 연결 해제, 사용자 삭제 CASCADE
- 제공 `tests_v5.sql`과 `tests_concurrency.sh` 원본 실행. 셸 스크립트는 임시 복사본의 줄바꿈만 LF로 정규화
- local 프로필의 API 계정 연결·JPA 초기화·HTTP 기동 및 기존 health 응답 확인

상세 보고서는 Git 제외 경로 `build/db-verification/<고유 컨테이너 이름>/`에 남는다.
기존 HTTP/컨텍스트 테스트는 test 리소스에서 DB 자동 구성만 제외해 DB 없이 실행하며,
공통 응답·Swagger·prod 동작 검증은 유지한다. H2로 PostGIS 동작을 대체하지 않는다.

### V1~V4 초기 구현에서 실행한 결과

2026-11-03, Windows/Java 21에서 다음을 확인했다.

| 항목 | 결과 |
| --- | --- |
| `test bootJar` | 기존 테스트 35개, 실패 0; 실행 JAR 생성 성공 |
| 실제 DB | PostgreSQL 16.15 / PostGIS 3.6.4 (`postgis/postgis:16-3.6`) |
| 빈 DB / 재실행 | V1~V4 적용 성공 / `No migration necessary` |
| 카탈로그 | 업무 테이블 25, CHECK 33, FK 37, PK 25, UNIQUE 제약 7, 함수 11, 트리거 8 확인 |
| 인덱스·권한·보충 도메인 검사 | 모두 통과; API/마이그레이션 각각 SCRAM 비밀번호 인증으로 연결 |
| 제공 SQL 회귀 테스트 | 36 PASS, 0 FAIL |
| 동시성 | C1·C2 모두 PASS; 실제 Lock 대기 확인 |
| local HTTP | API 계정으로 JPA 초기화 및 `/api/v1/health` 정상 응답 |

최종 상세 로그는 `build/db-verification/parking-flyway-verify-0cc4913ccc/`에 있다.
기존 개발 DB·볼륨은 변경하지 않았다. 실제 IDE 실행 구성 조작과 개발 DB에의 적용은
실행하지 않았으며, 동일 local 프로필의 빌드 JAR로 기동을 검증했다.

## 후속 작업과 미결사항

아직 엔티티가 없어 앱 기동과 `validate` 성공은 전체 JPA 매핑 검증이 아니다.
엔티티 구현 시 공간 매핑과 모든 컬럼/FK를 실제 PostgreSQL에서 검증해야 한다.
GPS 정확도 정책, MAC 미저장 API 계약, 시설 범위, 위치 데이터 파기/보관,
오래된 요청 알림 기준, 모델 경계·공휴일·신고 반경은 이번 작업에서 정하지 않았다.
`bluetooth_address`는 유지하고 `notify.expiresAt` 또는 선택적 delivery CHECK 등을 추가하지 않았다.
기준 SQL 주석의 정책 문구도 원문 보존이며 새로운 정책 확정을 뜻하지 않는다.
배포용 자격증명 관리·별도 마이그레이션 실행·역할 접근 감사는 후속 운영 구성에서 다룬다.
