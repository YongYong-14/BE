## 개요

카카오 로그인과 인증·회원 관리 API 9개를 구현합니다. 공통 JWT·응답 형식·JPA Auditing을 재사용하며, 최신 dev의 차량 API와 통합했습니다. 실제 카카오 계정 연동과 정책 확정은 후속 작업입니다.

## 변경 사항

- 카카오 액세스 토큰의 앱 ID·만료·사용자 ID 검증, 가입/재로그인 및 기본 알림 설정 생성.
- 서비스 액세스 JWT 발급, 리프레시 토큰 SHA-256 해시 저장, 회전·재사용 감지·계열 폐기와 로그아웃.
- 내 정보, 유형별 최신 약관 이력과 동의 기록, 알림 설정 조회/부분 수정, 탈퇴와 카카오 연결 해제 웹훅.
- 사용자 행 잠금과 트랜잭션으로 동시 가입·재발급·탈퇴 충돌 방지. 웹훅 DB 처리 제한 및 민감정보 없는 오류 로그.
- PostgreSQL 오류 상세 숨김, Swagger·보안 회귀 검증, 프론트 인증 연동 가이드 추가.
- 최신 origin/dev `025a16d` 병합. 설정 주석과 Swagger 경로 수 충돌을 양쪽 기능을 보존하여 해결했습니다. 차량 HTTP 테스트에는 신규 계정 Repository mock만 추가했습니다.
- 로그인 JWT로 차량 등록·조회, 타 사용자 격리, hasVehicle, 회원 삭제 후 차량 CASCADE 및 기존 JWT 거부를 실제 격리 DB에서 검증했습니다.
- 기존 V1~V5 마이그레이션과 차량 비즈니스 로직은 유지합니다. 최신 dev에 주차 위험 판정 API 구현은 없어 해당 API의 실제 HTTP 연동은 검증하지 못했습니다. 위험 판정·근거·위치 로그의 FK 삭제 동작은 인증 DB 테스트에서 검증합니다.

## 관련 이슈

Refs #19

## 테스트

아래 완료 표시는 로컬 일반 테스트·Mock 카카오·격리 PostgreSQL 검증 범위입니다.

- [x] 로컬 환경에서 테스트 완료: 일반 68개, PostgreSQL 30개, 실패·오류·스킵 0
- [x] 기존 기능 정상 동작 확인: 공통 JWT/보안/Swagger/Auditing/차량 회귀 검증
- [x] 애플리케이션 정상 실행 확인: 실행 JAR, 신규 격리 DB Flyway V1~V5, Hikari/JPA 초기화
- [x] 관련 API 요청/응답 확인: 담당 OpenAPI operation 9개, 보호 API 6개 401, 잘못된 Bearer 401, 카카오 미설정 503, 빈 재발급 400, 잘못된 웹훅 매체 415
- [x] DB 저장/조회 정상 동작 확인: 앱 역할 권한, 동시성, 해시 저장, 회전/재사용 폐기 커밋, 계정/차량 격리와 FK 연쇄 삭제
- [x] check, bootJar 및 PostgreSQL 테스트 컴파일 통과
- [x] 커밋 대상 민감정보·환경파일·DB 파일·빌드 산출물 제외 및 diff 공백 검사
- [ ] 실제 카카오 계정 로그인·탈퇴·웹훅 테스트
- [ ] 운영 프록시/APM의 민감 헤더·본문 수집 제외 및 웹훅 전체 응답 시간 확인

### 테스트 방법

2026-11-06, Java 21(Microsoft 21.0.11), Gradle 9.8.2, Docker 29.8.0에서 실행했습니다.

```powershell
$env:JAVA_HOME = 'C:\Users\user\.jdks\ms-21.0.11'
$env:PATH = 'D:\Docker\app\resources\bin;' + $env:PATH
.\gradlew.bat check bootJar compilePostgresTestJava --offline --no-daemon --rerun-tasks
.\scripts\db\Verify-Authentication.ps1 -Account -JavaHome $env:JAVA_HOME
.\scripts\db\Verify-Authentication.ps1 -JavaHome $env:JAVA_HOME
.\scripts\db\Verify-Auditing.ps1 -JavaHome $env:JAVA_HOME
.\scripts\db\Verify-Vehicle.ps1 -JavaHome $env:JAVA_HOME
.\build\pr-preparation\Verify-AccountHttp.ps1 -Isolated
```

일반 테스트 68개(카카오 HTTP Mock 9개 포함), 인증 PostgreSQL 17개, 기존 사용자 존재 확인 1개, Auditing 4개, 차량 8개가 통과했습니다. PostgreSQL 스크립트는 신규 tmpfs 컨테이너·임시 loopback 포트·임시 자격증명을 사용합니다. 기존 로컬 DB와 Docker 볼륨은 초기화하거나 삭제하지 않았습니다.

마지막 HTTP 명령은 기존 로컬 보조 스크립트를 Windows PowerShell 5의 HttpClient 및 ProcessStartInfo에 맞게 조정한 Git 제외 로컬 검증 도구입니다. 해당 파일은 PR에 포함하지 않습니다. 신규 격리 DB에서 실행 JAR를 시작해 Flyway/JPA 초기화, health·Swagger/OpenAPI 200, 담당 9개 operation 등록과 위 상태 코드를 검사했습니다. 최초 HTTP 도구의 오류 응답 본문 읽기 실패는 호환성을 수정한 후 재실행해 통과했습니다. 초기 Flyway SQLSTATE 01007 경고는 있었으나 V1~V5 적용·기동·HTTP 검사는 통과했습니다.

로컬 근거(산출물은 Git 제외):
- `build/pr-preparation/check.log`, `build/test-results/test/`
- `build/auth-verification/parking-auth-49e98ba10f/`: 인증·차량 연동 17개
- `build/auth-verification/parking-auth-ce64931216/`: 사용자 존재 확인 1개
- `build/auditing-verification/parking-auditing-3d23c7fb1f/`: Auditing 4개
- `build/vehicle-verification/parking-vehicle-a7473e0fa1/`: 차량 8개

## 미완성 사항 및 후속 작업

- 위치정보 동의 철회 시 데이터 파기 정책 미확정으로 현재 503 반환. 동의 이력을 성공으로 기록하지 않으며, 파기 범위·권한·수집 차단 시점을 팀에서 확정해야 합니다.
- 실제 카카오 계정 로그인·탈퇴·웹훅 테스트 미실시. Mock 및 격리 DB 검증을 실연동 완료로 간주하지 않습니다.
- 약관 현재 버전 확정 필요. 종류별 허용 버전 목록의 첫 값을 현재 버전으로 사용하므로 배포 설정과 프론트 표시를 맞춰야 합니다.
- 프론트엔드 중복 토큰 재발급 방지 연동 필요. 같은 토큰의 중복 재발급/응답 유실 후 재전송은 재사용 감지와 계열 폐기를 유발할 수 있습니다.
- 웹훅 삭제 실패 시 재처리 방식 추가 검토 필요. 인증된 콜백은 DB 실패에도 200을 반환하므로 자동 재시도는 보장되지 않습니다. 실패 경보·보호된 재처리 경로·지연 웹훅과 재가입 정책을 확정해야 합니다.
- 직접 탈퇴 시 카카오 연결 해제 실패에도 내부 계정 삭제를 진행합니다. 반대로 카카오 해제 후 내부 DB 장애가 나면 상태 불일치 복구가 필요합니다.
- 로그아웃 후 기존 액세스 JWT는 원래 만료까지 유효합니다. 회원 삭제 후에는 DB 존재 검증으로 거부됩니다.
