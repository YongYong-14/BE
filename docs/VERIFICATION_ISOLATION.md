# DB 검증 스크립트의 연결 격리

`Verify-Database.ps1`은 부모 프로세스 환경을 수정하지 않는다. 생성한 임시 컨테이너에만
관리자 SQL을 실행하고, Java 프로세스는 `ProcessStartInfo.EnvironmentVariables.Clear()` 후
OS 실행에 필요한 변수와 이 실행에서 만든 검증 입력만 전달한다.
부모의 `SPRING_*`, `SPRING_APPLICATION_JSON`, DB 자격증명, `CLASSPATH`,
`JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS`, `JAVA_OPTS`는 전달하지 않는다.

검증용 `VerificationLauncher.java`는 빌드 JAR에서 추출한 클래스/라이브러리로 실행한다.
애플리케이션 소스, 배포 JAR, V1~V5 마이그레이션에는 포함하거나 변경하지 않는다.
소스 실행에 Java 21 JDK가 필요하다. 작업 디렉터리는 해당 실행의 build 하위 폴더이며
Spring 설정 탐색 위치를 `classpath:/`로 고정해 현재 디렉터리와 부모가 지정한 외부 설정을 제외한다.
Spring 프로필은 local로 고정하고 DataSource/Flyway URL·계정·비밀번호는 최우선 속성 소스로 고정한다.
JVM에는 테스트 비밀번호를 프로세스 환경으로 전달하며 로그나 보고서에 출력하지 않는다.
저장 전 JVM 로그에서도 생성한 비밀번호를 마스킹한다.

`FlywayMigrationStrategy`는 **flyway.migrate() 호출 전에** 실제 API DataSource와
Flyway DataSource의 JDBC URL, `current_database()`, `current_user`, 실행별 임의 표식을 확인한다.
표식은 임시 컨테이너 안에서 `ALTER DATABASE ... SET verification.identity`로 설정한다.
두 연결의 대상 URL/DB/역할/표식이 일치해야 마이그레이션을 허용한다.
확인 쿼리는 읽기 전용 트랜잭션에서 수행하며, 실패하면 마이그레이션하지 않는다.
음성 대조군은 올바른 URL에 잘못된 표식을 전달해 이력/업무 테이블 생성 전에 중단되는지 검사한다.

HTTP 검증도 같은 실행 경로를 사용한다. 부모의 JVM 옵션을 상속하는 별도 Start-Process 경로는 없다.
각 JVM의 표준 출력/오류를 비동기로 수집하며 자신이 시작한 JVM만 종료한다.

## 회귀 검증

```powershell
./scripts/db/Test-VerificationIsolation.ps1 -Java '자신의 Java 21 JDK/bin/java.exe'
```

이 하네스는 별도 tmpfs 미끼 컨테이너를 만들고 canary 스키마·데이터와 Flyway 이력 표식을 넣는다.
실제 개발/운영 DB의 주소나 `.env`는 읽지 않는다. 부모 프로세스에 다음 오염 설정을 주입한다.

- `SPRING_DATASOURCE_*`, Hikari JDBC URL, `SPRING_FLYWAY_*`
- `SPRING_APPLICATION_JSON`
- 외부 `spring.config.location`, `additional-location`, `import`, 설정 이름과 프로필
- `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS`, `JAVA_OPTS`, `CLASSPATH`
- 미끼 DB를 가리키는 기존 DB 연결 변수

오염 환경과 정상 환경 각각에서 전체 Verify-Database 검증을 실행한다.
각 실행 전후 부모 환경의 **모든 이름과 값**을 비교하고, 미끼 DB 전체 SQL dump의 스키마/데이터/
Flyway 이력을 대조한다. dump의 임의 `\restrict` 키만 제거하고 문자열 전체를 비교한다.
미끼 PostgreSQL의 접속 로그에 오염 URL의 애플리케이션 이름이 없는지도 확인한다.
검증 대상 임시 DB의 V1~V5 적용·이관·원문 보호·회귀 SQL·동시성·HTTP 기동이 모두 성공해야 통과한다.
하네스가 의도적으로 설정한 부모 환경은 실패 시에도 원상 복원하고 자신의 미끼 컨테이너만 정지한다.

결과와 상세 로그는 Git에서 제외되는 `build/db-verification/`에 보관한다.

## 실제 실행 결과 (2026-11-03)

PostgreSQL 16.15 / PostGIS 3.6.4 / Java 21에서 다음을 확인했다.

| 조건 | 전체 검증 | 부모 환경 전체 보존 | 미끼 스키마·데이터·Flyway 이력 | 오염 URL 접속 |
| --- | --- | --- | --- | --- |
| Spring/JSON/외부 설정/JVM 옵션 오염 | PASS | 동일 | 동일 | 없음 |
| 정상 환경 | PASS | 동일 | 동일 | 없음 |

두 실행에서 각각 V1~V5 전체 적용, V4→V5 이관, 재실행 무적용, 원문/빈 문자열 보호,
SQL 회귀 36개, 동시성 C1/C2, local HTTP 기동이 통과했다.
각 실행의 JVM 9개 모두 실제 두 연결을 사전 확인한 뒤 Flyway를 실행했다.
잘못된 표식의 음성 대조군은 두 실행 모두 Flyway 이력/업무 테이블 생성 전에 중단됐다.

결과 JSON: `build/db-verification/parking-decoy-561cd8126d/results.json`

- 오염 환경 상세: `build/db-verification/parking-flyway-verify-8e258e8984/`
- 정상 환경 상세: `build/db-verification/parking-flyway-verify-f8ef29623f/`

이번에는 스크립트와 검증용 실행기만 변경했으며, 앱 소스와 V1~V5 마이그레이션은 변경하지 않았다.
기존 JUnit 35개는 이번 수정에서 다시 실행하지 않았고, 위 DB/기동 검증은 실제로 재실행했다.
개발·운영 DB에는 연결하지 않았으며 하네스가 생성한 컨테이너는 종료했다.
