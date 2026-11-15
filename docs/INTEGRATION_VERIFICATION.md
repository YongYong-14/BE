# 이슈 #27 통합 검증 (2026-11-07)

## 대상과 환경

- 작업 브랜치: `task/27-integration-verification`. 브랜치를 만들거나 전환하지 않았다.
- 원격 fetch 후 기준 HEAD와 origin/dev: `dff0f4fad81ce9a8865d49312ccb16f99bd0e2e5`.
- 시작 시 미커밋 변경 없음. 저장소·상위 경로에서 AGENTS.md를 찾지 못했다.
- Java 21, PostgreSQL 16.15 / PostGIS 3.6 계열, Flyway V1~V6.
- 최신 DOCX 본문 v0.10, PARKING_EVENTS.md, MAP_RISK_ZONES.md, 실제 컨트롤러·권한·기존 검증 실행기를 대조했다.
- 주차장 적재·주변 주차장 API·LLM 리포트는 제외했다.

| PR | origin/dev의 실제 병합 커밋 | 범위 |
| --- | --- | --- |
| #18 | `42941fb` | 주차 저장·판정 |
| #22 | `3b19221` | 인증·계정 |
| #24 | `d0e386f` | 지도 |
| #26 | `dff0f4f` | 최근·상세·목록·delivery |

네 PR 모두 최신 origin/dev의 조상이다. 미병합으로 제외한 관련 PR은 없으며 임의 병합하지 않았다.

## 재현한 결함과 수정

### 실제 Spring Repository 프록시에서 주차 저장 500

기대: 내부 로그인 → 현재 위치 약관 동의 → 차량 등록 뒤 유효한 주차 요청이 201과 저장 판정을 반환한다.

실제: 인증·계정·차량은 처리됐으나 주차 저장은 500이었다. `ParkingAssessmentEngine.snapshot()`에서
`store.jdbc`를 직접 읽을 때 NullPointerException이 발생했다. 이 경로에서 이벤트 저장도 롤백됐다.

재현: 수정 전 코드에서 `Verify-ParkingEvents.ps1 -Integration`을 실행하면 정상 저장이 필요한
연결 테스트 5개가 실패한다. 요청 payload는 진단에 출력하지 않는다.

원인: `@Repository`의 예외 변환이 만드는 CGLIB 프록시와 실제 초기화된 Repository 대상은 서로 다른 객체다.
다른 빈에서 프록시의 필드를 직접 읽으면 대상의 생성자로 초기화한 JDBC 객체에 접근하지 못한다.
기존 주차 DB 테스트의 직접 생성 객체에서는 재현되지 않았다.

수정: JDBC 필드를 private으로 제한하고 `jdbc()`를 통해 대상 객체로 메서드 호출을 전달한다.
판정·조회·delivery·비동기 주소 갱신의 접근을 같은 방식으로 바꿨다.
SQL, 정책, 공개 응답, 스키마, 권한은 변경하지 않았다.

회귀: 새 `IntegratedJourneyPostgresTests`는 실제 Spring 컨텍스트·인증 필터·JPA·JDBC·트랜잭션·이벤트를 사용한다.
카카오 경계 이외의 계정·차량·주차·지도 서비스를 모킹하지 않는다.

### 참조 검증 실행 경로 보완

기존 덤프 지도 검증기는 V2~V5를 개별 SQL로 적용해 Flyway 이력과 V6 권한을 검증하지 못했다.
기존 실행기 안에서 환경변수 허용 목록과 실행 코드를 재사용하도록 정리하고,
두 DB 역할의 식별 검사 → 실제 Flyway V1~V6 → 기존 참조 데이터 복원 순서로 변경했다.
V6에서 허용한 report_aggregates SELECT를 금지하던 과거 테스트 기대값도 최신 권한 계약에 맞췄다.
마이그레이션 파일 자체는 수정하지 않았다.

## 연결 검증 결과

| 흐름 | 결과와 확인 내용 |
| --- | --- |
| 로그인·회원 생성·토큰 발급 | 내부 코드 통과. 외부 카카오 프로필 확인만 대체 |
| 위치 약관 | 미동의·이전 버전 동의는 주차 403, 현재 버전 동의 후 처리. 두 모듈이 동일 설정 사용 |
| 차량 | 미등록 조회 null, 동의 후 미등록 차량 주차 404, 등록·같은 MAC 수정·교체 확인 |
| 교체 전 이벤트 | 최초 차량명 snapshot과 기존 이벤트의 재요청·과거 조회 유지 |
| 멱등성 | 동일 본문 기존 결과, 변경 본문 409, 동시 저장은 201/200과 동일 이벤트·판정 |
| 저장·최근·상세·목록 | 저장 상태·등급·공통 판정 대조. PARTIAL/FAILED의 null 등급과 목록 표현 유지 |
| FAILED 재요청 | 허용된 1회 재판정 뒤 attemptCount=2, 이후 저장 결과 유지 |
| 상세 peak | 저장 모델 원천·정확한 기간·공휴일 요일의 직접 SQL 집계와 슬롯별 대조 |
| 모델 교체 | latest.dataPeriod는 현재 활성 모델, 과거 상세·peak·재요청은 저장 참조 유지. 신규 이벤트만 새 모델 사용 |
| 지도·판정 | 같은 격자·시각·모델의 raw top_percent 및 임계값 대조. 임계값과 같은 경우도 주의 칸 포함 |
| 시설 우선 판정 | 모델상 주의 칸이 없어도 시설 규칙으로 1단계·SKIPPED 가능함을 별도로 확인 |
| delivery | NONE은 409, 실패→성공, 최초 성공 보존, 중복·동시 요청 204와 빈 본문, 잘못된 입력의 저장값 불변 |
| 사용자 A/B | 타인 상세·delivery 404, 타인 목록 미노출 |
| refresh·로그아웃 | 회전·재사용 탐지·후속 토큰 폐기. 로그아웃 후 refresh 거절, 기존 access는 만료까지 유효한 계약 확인 |
| 탈퇴 | 기존 토큰의 다른 모듈 접근 401, 계정·차량·주차·판정·약관·refresh·위치 로그 등 연관 데이터 삭제 |
| 비동기 주소 | 실제 커밋 후 작업이 외부 대체 주소를 반영. 판정·멱등성·PROVIDE 로그 1회 유지 |
| 위치 동의 철회 | **알려진 미완료**: 503을 확인했으며 철회·데이터 파기 기능을 통과로 처리하지 않음 |

새 연결 테스트는 MockMvc로 실제 애플리케이션 필터부터 트랜잭션까지 실행한다.
실제 네트워크·브라우저·모바일 앱 시연을 대체하지 않는다. 일반 테스트의 개인정보 보호 회귀는
기존 실제 loopback HTTP 테스트를 포함한다.

실제 덤프 참조 검증은 합성 연결 검증과 구분한다. 허용된 참조 테이블만 새 tmpfs DB에 복원하고,
사용자·차량·주차·인증 데이터·덤프 DDL·ACL·함수·Flyway 이력은 복원하지 않는다.
공개 규칙 적용과 검증용 사용자 생성은 격리 DB에서만 수행한다. 실제 모델 기반 저장 판정과
상세 peak의 원천·기간별 SQL 집계, 지도 조건 및 멱등성을 서비스·DB 경계에서 대조한다.
덤프 검증 경로는 전체 로그인 HTTP 여정을 실행하는 경로와 별도다.

## 재현 명령과 결과 보관

| 최종 검증 | 결과 | 로컬 증거 |
| --- | --- | --- |
| 일반 테스트 | 126개 통과, 실패·오류·skip 0 | `build/test-results/test/` |
| 전체 연결 | 5개 시나리오 통과, 실패·오류·skip 0 | `build/parking-event-verification/parking-parking-event-329aafbac8/tests.xml` |
| 기존 주차 DB 회귀 | 42개 통과, 실패·오류·skip 0 | `build/parking-event-verification/parking-parking-event-93f294d584/tests.xml` |
| 기존 계정 DB 회귀 | 17개 통과, 실패·오류·skip 0 | `build/auth-verification/parking-auth-e654b6509b/tests.xml` |
| 지도 경계·인덱스 회귀 | 1개 통과 | `build/map-verification/parking-map-433233f48d/tests.xml` |
| 실제 참조 데이터 + 판정·peak·지도 | 1개 통과, 별도 Flyway 사전 검증 통과 | `build/map-verification/parking-map-7e9633a4e0/tests.xml`, `migration.log` |
| 빌드·컴파일·diff | `test build compilePostgresTestJava`, `git diff --check` 통과 | `build/libs/parking-server-0.0.1-SNAPSHOT.jar` |

실제 참조 검증에서 일반 화면의 주의 칸 66개 신고 건수를 직접 집계와 비교했고,
별도 주차 이벤트의 저장 판정·non-null peak·지도 임계값을 실제 참조 행과 대조했다.
최대 2km 화면의 실제 SQL 실행 계획도 확인했다. 이 수치와 측정 시간은 해당 데이터·격리 환경의 결과이며 부하 보장이 아니다.
원본 덤프의 검증 전후 SHA-256은 모두
`094FE95EDD699110E472D1ADB68CD9AB6C6CD1E79F8C5929145EF37789928F14`로 같았다.
최종 실행에서 남은 예상 밖 실패는 없다. 실제 카카오·실기기·배포 환경 미검증과 철회 미완료는 아래에 별도로 남긴다.

Java 21 경로를 지정해 **순차 실행**한다. 같은 저장소에서 postgresTest를 동시에 실행하면
Gradle의 결과 파일 경로가 충돌한다. 이 실행 충돌은 애플리케이션 결함과 구분했다.

```powershell
./scripts/db/Verify-ParkingEvents.ps1 -JavaHome <Java21> -Integration
./scripts/db/Verify-ParkingEvents.ps1 -JavaHome <Java21>
./scripts/db/Verify-Authentication.ps1 -JavaHome <Java21> -Account
./scripts/db/Verify-MapRiskZones.ps1 -JavaHome <Java21>
./scripts/db/Verify-MapRiskZones.ps1 -JavaHome <Java21> -ReferenceDump docs/reference/yongin_parking_20261106.dump
./gradlew.bat test build compilePostgresTestJava --no-daemon
git diff --check
```

실행기는 매번 새 tmpfs 컨테이너를 만들고, 부모의 Spring/JVM/DB 환경변수를 차단하며
loopback URL·DB 이름·역할·실행별 verification.identity를 검사한다.
연결 테스트 앱은 parking_api, Flyway·fixture 적재는 parking_migrator다.
기존 계정 전용 회귀 실행기는 기존 계약대로 auth_app을 사용하며 전체 연결 경로의 권한 검증과 구분한다.

결과 XML·실행 로그는 Git 제외 경로 `build/parking-event-verification/`, `build/auth-verification/`,
`build/map-verification/`에 보관한다. 테스트에서는 요청·응답 자동 출력을 끄고,
DB DETAIL 및 임시 기본 보안 비밀번호 로깅을 억제한다. 보고서에 좌표·토큰·비밀번호·키는 기록하지 않는다.

## 시연·배포 전에 남은 일

1. 서버와 앱의 약관 버전을 맞춘다. 최신 명세의 개발 약관 버전은 `2026-11-06-v1`이며,
   TERMS·PRIVACY·LOCATION_SERVICE의 배포 설정과 앱 요청을 함께 확인해야 한다. 테스트는 별도 fixture 버전을 쓴다.
2. 배포 DB에서 정상 Flyway V6 적용과 parking_api 역할을 확인한다. 이번 작업은 배포 DB를 검증하지 않았다.
3. 배포 환경의 공개 규칙 키·공개 시각, 활성 모델과 활성 신고 원천, 모델 동결·전체 슬롯 적재 상태를 확인한다.
   실제 데이터에 없는 모델이나 안전 판정을 테스트가 임의로 보충하지 않는다.
4. 실제 카카오 앱 ID·유효 토큰·리다이렉트/앱 설정·관리 키·unlink와 webhook을 외부 연동 환경에서 확인한다.
   이번 전체 여정의 verify/unlink는 대체이며 실제 카카오 검증 통과가 아니다.
5. Kakao Local 실제 주소 조회·타임아웃·쿼터는 미검증이다. 비동기 실행·커밋·로그·판정 보존은 대체 응답으로 검증했다.
6. 프론트에서 OS 알림 권한, SEND 표시·delivery 보고 시각, 중복 표시 방지, 로그아웃·탈퇴 시 토큰 제거를 확인한다.
   지도에 칸이 없다는 이유로 안전이라 표시하지 않고 PARTIAL/FAILED를 별도 표현한다.
7. CORS·TLS·리버스 프록시 로그·실기기 네트워크·동시 부하·배포 키·시크릿 관리는 실제 환경에서 확인한다.
8. 위치 동의 철회와 연관 위치 데이터 파기는 알려진 미완료다. 정책 확정·구현·회귀 검증 없이 외부 공개 완료로 간주하지 않는다.

개발·운영 DB, 기존 볼륨, 실제 .env, 기존 Flyway SQL, DBML, 원본 덤프는 변경하지 않았다.
커밋·푸시는 하지 않았다.
