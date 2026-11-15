# 인증 공통 기반과 계정 담당자 연결 계약

기준은 Java 21 / Spring Boot 4.1.2, API v0.6 / ERD v5.1, Flyway V1~V5다.
기존 인증·사용자 조회 코드는 없었다. 공통 응답과 예외 구조는 재사용한다.
JJWT 0.13.0의 API/impl/Jackson 모듈을 사용한다. JJWT 내부는 Jackson 2,
애플리케이션 응답 작성은 Boot 4의 Jackson 3으로 분리된다.
[공식 사용 근거](https://github.com/jwtk/jjwt): verifyWith, 알고리즘 제한, Clock 주입.

## 문서 정합성과 담당 경계

DOCX 파일명·reference/README는 v0.6 / ERD v5.1인데 본문 첫 제목은 v0.4 / ERD v5로 남아 있다.
본문은 모든 경로가 /api/v1 아래라고 명시한다. 실제 경로·오류 코드와 요청자가 확정한 아래 계약을 따른다.
문서 관리자와 제목 버전 표기를 정리해야 한다. ERD·기존 마이그레이션은 변경하지 않았다.

- 공통 모듈은 **액세스 JWT만** 발급·검증한다. 기본 수명은 30분이다.
- 재원님 담당 리프레시는 **불투명 난수 토큰**이며 기본 수명은 30일이다.
- 리프레시 생성·해시 방식·저장·회전·계열 폐기·재사용 탐지는 계정 기능에서 구현한다.
- 원문은 응답과 해시 계산에만 사용하고 DB에는 해시만 저장한다.
- 계정 서비스는 검증된 서비스 사용자 UUID로 발급을 요청한다. 카카오 ID를 subject로 사용하지 않는다.
- refresh_tokens의 family_id/replaced_by_id/revoked_at 연결과 동시성·트랜잭션도 계정 담당자 책임이다.
- 로그아웃 후 이미 발급된 액세스는 만료까지 유효하다. 탈퇴로 users가 하드 삭제되면 존재 조회로 차단한다.

카카오 로그인·재발급 API, 로그아웃·탈퇴·약관·차량·주차 API는 구현하지 않는다.
계정 도메인 엔티티나 Repository 전체를 대신 구현하지 않는다.

## 실제 제공 API

패키지는 `io.github.yongyong14.parking_server.global.security`다.

```java
// AccessTokenService 빈을 생성자 주입한다.
AccessTokenService.IssuedAccessToken issued = accessTokenService.issue(userId);
String accessToken = issued.accessToken();
long expiresIn = issued.expiresIn(); // 초, 기본 1800
Instant expiresAt = issued.expiresAt();
UUID verifiedUserId = accessTokenService.verify(accessToken);
```

실제 시그니처:

```java
public IssuedAccessToken issue(UUID userId)
public UUID verify(String token)
public record IssuedAccessToken(String accessToken, long expiresIn, Instant expiresAt)
```

반환 record는 AccessTokenService의 중첩 타입이다. `verify`는 암호학적 검증만 수행한다.
HTTP 필터는 추가로 `UserExistence.exists(UUID)`를 호출한다. 계정 응답의 isNewUser/user/agreements는 계정 담당자가 구성한다.
`TokenSettings.refreshTtl()`은 계정 모듈이 불투명 리프레시 만료 계산에 사용할 Duration이다.
JWT 모듈은 리프레시를 생성·검증하지 않는다. 원문을 로그에 쓰지 않으며 토큰 record와 설정의 toString은 민감정보를 숨긴다.

```java
@GetMapping("/api/v1/me")
ApiResponse<MyResponse> me(@CurrentUser UUID userId) {
    return ApiResponse.success(myService.find(userId));
}
```

이는 문서 예시다. 실제 보호 확인용 컨트롤러는 테스트 소스에만 있다.
`@CurrentUser`는 UUID 매개변수에 사용하며 인증된 SecurityContext의 UUID만 주입한다.
미인증은 null이나 임의 UUID로 대체하지 않는다. 본문·쿼리의 userId를 현재 사용자로 신뢰하지 않는다.
리소스 소유권은 각 API에서 검사한다. 자동 ADMIN 권한 등은 부여하지 않으며 `@PreAuthorize`를 사용할 수 있다.

## 환경변수와 로컬 실행

| 변수 | 값/의미 |
| --- | --- |
| JWT_ISSUER | 필수, 서비스별 issuer, 발급·검증 일치 |
| JWT_SECRET_BASE64 | 필수, 독립적으로 생성한 최소 32바이트 난수의 Base64 |
| JWT_ACCESS_TTL | 기본 30m, 양의 정수 초 단위 Duration |
| AUTH_REFRESH_TTL | 기본 30d, 계정 모듈용 양의 Duration |
| CORS_ALLOWED_ORIGINS | 기본 빈 값, 실제 브라우저 Origin을 쉼표로 구분 |
| SPRING_PROFILES_ACTIVE | 로컬은 local |

운영 설정에 키의 임의 기본값·샘플 키는 없다. issuer/키 누락, 잘못된 Base64,
32바이트 미만, 명백하게 반복된 키, 잘못된 수명은 시작 시 실패한다.
검증이 난수 품질 자체를 보장하지 않으므로 CSPRNG로 생성한다. 테스트 classpath 설정만 Spring random을 사용한다.
공유 키로는 토큰 발급도 가능하므로 환경별 별도 키를 비밀 관리 도구로 주입한다.
토큰·키를 로그·명령 인자에 넣지 않는다. 키 교체·다중 키 운영 정책은 후속 운영 계약이다.

```powershell
# Java 21, 기존 DB 계정 설정과 .env 준비는 DATABASE.md를 따른다.
. ./scripts/Import-LocalEnv.ps1
# 현재 프로세스에만 생성하고 콘솔에 출력하지 않는다.
$env:JWT_SECRET_BASE64 = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$env:JWT_ISSUER = 'parking-server-local'
./gradlew.bat bootRun
```

Import 후 설정하므로 .env의 빈 키가 생성한 값을 덮어쓰지 않는다.
구형 Windows PowerShell/.NET에서 정적 GetBytes(int)를 제공하지 않으면 다음을 대신 사용한다.

```powershell
$jwtBytes = New-Object byte[] 32
$jwtRng = [Security.Cryptography.RandomNumberGenerator]::Create()
$jwtRng.GetBytes($jwtBytes)
$env:JWT_SECRET_BASE64 = [Convert]::ToBase64String($jwtBytes)
$jwtRng.Dispose()
[Array]::Clear($jwtBytes, 0, $jwtBytes.Length)
```

외부 키가 없으면 health만 쓰는 실행도 시작되지 않는다. DB 앱/마이그레이션 계정과 실행 방법은
[DB 가이드](DATABASE.md)를 따른다.

## 공개 경로와 보안 설정

| 메서드 | 경로 | 상태 |
| --- | --- | --- |
| GET | /api/v1/health | 기존 구현, DB 상태 검사는 하지 않음 |
| POST | /api/v1/auth/kakao | 계정 담당자 구현 예정 |
| POST | /api/v1/auth/refresh | 계정 담당자 구현 예정 |
| POST | /api/v1/webhooks/kakao/unlink | 계정 담당자 전용 인증 구현 예정 |

공개 경로에서는 잘못되거나 만료된 Authorization 헤더도 **무시**한다.
서비스 SecurityContext를 구성하거나 사용자 조회를 하지 않는다. 미구현 API는 MVC 공통 404다.
다른 메서드·미등록 경로는 인증을 요구한다. health POST도 미인증이면 401이며 인증 후 MVC에 도달하면 기존 405/Allow 계약을 유지한다.

웹훅 제외는 카카오 인증 구현이 아니다. 계정 담당자가 카카오 전용 인증을 수행한 뒤 계정 삭제 서비스에 연결해야 한다.
서비스 Bearer는 무시하므로 웹훅 인증 근거로 사용할 수 없다. 이번 변경에는 삭제 기능이 없다.

Swagger는 local에서 GET 문서·정적 자산만 공개한다. 기본 프로필은 비공개다.
prod는 공개 설정을 켜도 공개하지 않으며 springdoc도 꺼져 있다.
미인증 prod 문서 접근은 401, 인증 후에는 매핑이 없어 404다. 테스트만 명시적으로 문서를 공개한다.
OpenAPI에 bearerAuth를 등록하고 공개 API는 security=[]로 표시한다. 보호 작업에만 인증 요구를 표시한다.

Stateless이며 세션·요청 캐시·폼 로그인·HTTP Basic·기본 로그아웃을 사용하지 않는다.
인증은 브라우저가 자동 첨부하는 쿠키가 아닌 Authorization Bearer 헤더이므로 CSRF를 비활성화했다.
쿠키 인증 도입 시 CSRF 정책을 다시 검토해야 한다.
클라이언트 Origin 요구는 저장소에 없으므로 CORS 기본은 비허용이다. 실제 주소를 팀과 확인해 정확히 설정한다.
와일드카드 Origin은 거부하고 credentials=false다. 허용 preflight는 CORS 필터에서 처리한다.
일반 OPTIONS를 permitAll 하지 않는다. CORS 거절도 공통 FORBIDDEN JSON이다.

## 실패 전달과 사용자 조회

| 상황 | 응답 |
| --- | --- |
| 토큰 누락·위조·잘못된 claim/UUID/issuer/알고리즘/종류 | 401 UNAUTHORIZED |
| 불투명 리프레시 또는 refresh 종류 JWT로 보호 API 접근 | 401 UNAUTHORIZED |
| 올바른 계약의 액세스 만료 | 401 TOKEN_EXPIRED |
| users PK 미존재·탈퇴 하드 삭제 | 401 UNAUTHORIZED |
| 사용자 조회 DataAccessException | 500 INTERNAL_SERVER_ERROR |
| 인증 후 권한 실패 | 403 FORBIDDEN |

HS256만 허용하며 서명, issuer, exp, iat, sub의 정규 UUID 형식, token_type=access를 검증한다.
필수 claim 누락과 미래 iat도 거부한다. Clock 주입으로 만료 경계를 테스트한다.
401은 WWW-Authenticate: Bearer를 포함하며 로그인 페이지 리다이렉트는 없다.
응답은 `{success:false,data:null,error:{code,message}}`이며 내부 예외·토큰·키·SQL을 노출하지 않는다.

필터는 ExceptionTranslationFilter보다 앞서 실행되므로 자체 토큰/조회 오류를 SecurityErrorWriter로 직접 작성한다.
토큰 없는 요청과 웹 권한 오류는 entry point/access denied handler가 같은 작성기를 사용한다.
downstream chain은 필터의 try/catch 밖에서 실행하여 MVC·권한 예외를 토큰 오류로 바꾸지 않는다.
MVC 메서드 권한 예외는 기존 GlobalExceptionHandler에서 FORBIDDEN으로 매핑한다.

실제 조회 구현 JdbcUserExistence는 다음 SQL만 실행한다.

```sql
SELECT EXISTS (SELECT 1 FROM public.users WHERE id = :id)
```

ERD와 SQL에 users.deleted_at은 없고 탈퇴는 하드 삭제다. 계정 담당자가 같은 조회를 제공하면
UserExistence 빈 구현을 교체한다. soft delete를 도입하면 조회 조건도 함께 바꿔야 한다.
FK 위반을 UNAUTHORIZED로 포괄 변환하지 않는다. 인증 이후 삭제 경쟁·트랜잭션·잠금·소유권 검사는 각 도메인 책임이다.

## 검증 실행과 한계

```powershell
./gradlew.bat test bootJar compilePostgresTestJava --no-daemon
./scripts/db/Verify-Authentication.ps1 -JavaHome '자신의 Java 21 JDK 경로'
```

일반 테스트는 Docker를 요구하지 않는다. postgresTest는 기존 opt-in source set이며 test/check/build와 연결하지 않았다.
인증 실행기는 기존 Verify-Auditing 방식의 고유 tmpfs 컨테이너·임의 loopback 포트·전용 계정·DB identity 검증을 따른다.
자식 환경을 명시적으로 구성하며 .env·개발 DB·Compose 볼륨을 읽거나 변경하지 않는다.
실제 V2의 users DDL만 적용해 PK 조회·하드 삭제·DB 오류 전파를 검증하고 전체 DB 검증은 반복하지 않는다.
종료 시 생성한 컨테이너만 정지한다. tmpfs 데이터는 사라지고 정지한 컨테이너 항목과 검증 보고서는 남는다.

카카오 연동·리프레시 영속화/회전·웹훅 전용 인증·실제 브라우저 Origin·배포 비밀 관리/키 교체는 후속 작업이다.

### 이번 실행 결과 (2026-11-04)

- `check build compilePostgresTestJava --no-daemon`: 성공. 일반 테스트 48개, 실패/오류/skip 0. 실행 JAR 빌드 포함.
- `Verify-Authentication.ps1`: PostgreSQL 사용자 조회 통합 테스트 1개 통과, 실패/오류/skip 0.
- 격리 보고서: `build/auth-verification/parking-auth-fe0f19307e/` (Git 제외).
- 일반 테스트는 만료 경계·위조·알고리즘/claim/UUID 거부·불투명 리프레시 거부·UUID 주입·사용자 미존재·DB 장애·401/403 JSON·공개 경로/메서드·CORS 기본 거절·Swagger·기존 MVC 오류를 확인했다.
- 전체 DB/Auditing 검증과 실제 카카오·브라우저·운영 배포 연동은 이번 범위에서 실행하지 않았다.
- ERD·V1~V5·개발 DB·기존 볼륨은 변경하지 않았고 커밋·푸시·PR도 생성하지 않았다.
