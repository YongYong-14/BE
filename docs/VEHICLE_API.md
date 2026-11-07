# 차량 조회·등록·변경 구현 가이드

기준: API v0.8 3절, ERD v5.1, 실제 Flyway V1~V5. 8절 #5의 MAC 미저장 논의는
미결로 유지한다. MAC 필드 및 기존 DBML·마이그레이션은 변경하지 않는다.

## API 계약

- `GET /api/v1/vehicle`: 인증된 사용자의 `deleted_at IS NULL` 차량만 반환한다.
  미등록은 HTTP 200, `data={"vehicle":null}`이다.
- `PUT /api/v1/vehicle`: name, bluetoothAddress, 선택적 deviceType을 받는다.
  **백엔드 기본안**: v0.8 3절에는 PUT 성공 상태·본문이 없다. 요청자가 지정한 대로
  최초 등록·동일 MAC 수정·다른 MAC 교체 모두 HTTP 200으로 변경 후 활성 차량을 반환한다.
- 성공은 기존 `ApiResponse`의 `success=true, error=null`이고, data는
  `{"vehicle":{"id":"uuid","name":"차량 이름","bluetoothAddress":"대문자 MAC","deviceType":null}}`이다.
  Swagger는 조회 `VehicleSuccess`의 vehicle만 null을 허용하고 변경 `VehiclePutSuccess`는 활성 차량을 필수로 표시한다.
- `@CurrentUser UUID`만 사용한다. userId를 요청 본문·쿼리에서 읽지 않는다.
- 동일 MAC 비교는 현재 활성 행에만 수행한다. 동일하면 ID·createdAt을 보존하고 이름·종류를 수정한다.
  다르면 deleted_at 설정 → 명시적 flush → 새 UUID 행 persist → flush 순서다.
  같은 트랜잭션에서 실행하므로 신규 저장 실패 시 기존 삭제 표시도 롤백된다.
- 과거 행을 복구하거나 하드 삭제하지 않는다. parking_events의 차량 참조와
  vehicle_name_snapshot을 변경하지 않는다.

## 검증·오류

실제 V2와 DBML은 name varchar(100) NOT NULL, bluetooth_address varchar(17) NOT NULL,
device_type varchar(50) NULL, UUID PK 기본값 없음으로 일치한다. deviceType CHECK·enum은 없다.
UUID는 기존 문서/테스트의 UUID.randomUUID() 관례대로 앱에서 생성한다.
차량은 BaseTimeEntity를 상속하며 생성·수정 시각은 기존 JPA Auditing을 사용한다.

- name: 필수·Unicode 코드 포인트 최대 100자(PostgreSQL varchar 기준). 기존 공통 규칙은 Bean Validation 우선이며 이름 공백에 대한
  도메인 규칙은 없었다. **백엔드 기본안**으로 @NotBlank를 적용해 빈 문자열·공백만인 이름을 거부한다.
  앞뒤 공백을 자동 제거하지 않는다. 이름 정규화 정책을 추가로 확정하지 않았다.
- deviceType: null/생략 허용, Unicode 코드 포인트 최대 50자. 빈 문자열을 포함해 임의 문자열을 허용한다.
  CAR_AUDIO 등의 허용 목록을 만들지 않는다.
  길이는 기존 Hibernate Validator의 @CodePointLength로 검증하며 정규화하지 않는다.
  OpenAPI 3.1 요청 스키마는 required에서 deviceType을 제외하고 type을 string/null로 표시한다.
  응답에서도 deviceType은 string/null이다.
- MAC: 변환 없이 대문자 16진수 콜론 구분 17자만 허용한다. 누락·null·형식 오류는
  400 INVALID_BLUETOOTH_ADDRESS. 다른 필드 검증도 동시에 실패하면 MVC의 INVALID_INPUT이 우선할 수 있다.
- 나머지 본문/필수값/길이 오류: 400 INVALID_INPUT. 미인증: 401 UNAUTHORIZED
  (유효한 액세스 만료는 기존 TOKEN_EXPIRED).
- SQLState 23505이며 제약명이 정확히 uq_vehicles_active_user인 경우만
  무결성 오류를 409 VEHICLE_CONFLICT로 변환한다. 다른 무결성 오류는 공통 500이다.
  내부 SQL/실패 행 값을 공개하거나 예외 cause로 로깅하지 않는다.
  JDBC logServerErrorDetail=false로 PostgreSQL 실패 행 상세 출력도 차단한다.

## 동시성·잠금 순서

사용자 FOR SHARE → 활성 차량 조회 → 차량 UPDATE/INSERT 순서다. 사용자 공유 잠금은
탈퇴의 DELETE 및 동의 철회의 FOR UPDATE와 충돌하며, 차량 요청끼리는 직렬화하지 않는다.
사용자 조회·잠금은 최소 native query만 사용하고 User 엔티티·계정 서비스를 구현하지 않았다.
인증 후 사용자 삭제가 먼저 완료되었다면 PUT은 401이다.

최초 등록 경쟁은 기존 부분 유니크로 판정한다. 차량 수정·교체 경쟁은
Hibernate @OptimisticLocking(ALL) + @DynamicUpdate로 읽은 행 상태를 UPDATE 조건에 포함해
변경 유실을 막는다. 스키마 version 열을 추가하지 않는다. 다른 요청이 먼저 행을 바꾸면
낙관적 잠금 실패를 409 VEHICLE_CONFLICT로 반환한다. 자동 재시도/덮어쓰기는 하지 않는다.
이미 변경할 값이 없는 동일 요청은 dirty UPDATE가 발생하지 않을 수 있다.
이 낙관적 충돌 응답은 스키마 변경 없이 기존 동시 변경 충돌 계약을 적용한 백엔드 기본안이다.

예외 변환은 별도 트랜잭션 빈 호출이 롤백된 뒤 서비스에서 수행한다.
제약 위반 트랜잭션 안에서 재조회하지 않는다. 클라이언트는 409 후 재조회하고 재시도한다.

후속 주차 저장도 사용자 FOR SHARE → 활성 차량 FOR SHARE 순서를 지켜야 한다.
차량 UPDATE는 차량 FOR SHARE와 충돌하므로 주차 트랜잭션 종료까지 기다린다.
교체가 먼저 완료되면 후속 주차 저장은 옛 차량의 deleted_at 조건을 재검사해 거부해야 한다.
철회·탈퇴·주차 API는 아직 없으므로 실제 기능 간 통합 검증은 해당 구현 후 필요하다.

## 문서·코드 차이 및 미결

- v0.8 제목은 최신이지만 공통 규칙 본문에는 schema_v5.sql/ERD v5라는 옛 표기가 남아 있다.
  DATABASE.md·AUTHENTICATION.md·reference/README·DBML 메타 설명도 v0.6 기준 표기가 남아 있다.
  실행 스키마는 V1~V5, 차량 열/FK/부분 유니크는 v5.1 DBML과 일치한다.
- v0.8의 차량 이름 길이·공백 및 deviceType 허용 목록은 별도 정의가 없다.
  길이·nullable은 실제 DDL을 따르고 공백 처리와 PUT 응답 기본안은 위에 명시했다.
- v0.8 #5는 미결이며 등록/이름 수정 API 분리, MAC 제거는 구현하지 않았다.
- 저장소 루트·상위 경로에 AGENTS.md가 없었다. COMMON_API.md 및 기존 인증·Auditing·격리 규칙을 따랐다.
- 계정 User 엔티티나 계정 서비스는 현재 없으며 공통 인증은 JdbcUserExistence를 사용한다.

## 재현

```powershell
./gradlew.bat test bootJar compilePostgresTestJava --no-daemon
./scripts/db/Verify-Vehicle.ps1 -JavaHome '자신의 Java 21 JDK 경로'
```

Verify-Vehicle은 기존 Verify-Authentication 실행기 방식을 사용한다. 자식 환경을 초기화해
외부 Spring/JVM/DB 설정을 상속하지 않고, 고유 tmpfs 컨테이너·임의 loopback 포트·별도 계정을 사용한다.
JPA/DDL 실행 전 URL·DB·역할·실행별 표식을 검사한다. 실제 V2의 users/vehicles/parking_events DDL과
관련 FK·인덱스, V3의 부분 유니크를 그대로 추출한다. JPA ddl-auto는 validate다.
개발/운영 DB·Compose·기존 볼륨·.env를 사용하지 않는다. 종료 시 자신의 컨테이너만 정지한다.
보고서는 Git 제외 경로 build/vehicle-verification에 남는다.

HTTP 검증은 인증 실패, UUID 주입, 공통 JSON, MAC/이름/기기 종류 검증, 오류 분류, Swagger를 확인한다.
PostgreSQL 검증은 등록·조회·동일 MAC 수정의 ID/createdAt 보존, 교체·과거 MAC 재등록,
삽입 실패 롤백, 사용자 격리, 주차 FK/스냅샷 보존, 최초 등록/수정/교체 경쟁,
주차 FOR SHARE와 차량 교체 충돌을 확인한다.

## 실행 결과 (2026-11-05)

- `test bootJar compilePostgresTestJava --no-daemon`: 성공. 일반 테스트 55개, 실패 0.
- `Verify-Vehicle.ps1`: PostgreSQL 16.15에서 통합 테스트 8개, 실패/오류/skip 0.
  최종 보고서: `build/vehicle-verification/parking-vehicle-270fdfea1a/`.
- 최종 DB 검증 로그에 PostgreSQL 실패 행 상세/MAC 값이 출력되지 않는 것을 확인했다.
- 기존 전체 Flyway/권한/Auditing 회귀 스크립트와 환경 오염 미끼 하네스는 이번에 재실행하지 않았다.
  차량 실행기의 기존 환경 초기화·DB identity 보호는 유지했다.
- 실제 철회·탈퇴·주차 API 간 통합, IDE/운영 배포는 미검증이다.
- 개발·운영 DB/기존 볼륨은 사용하지 않았고 기존 SQL/DBML은 변경하지 않았다.
  커밋·푸시·PR은 생성하지 않았다.

### 리뷰 P2 수정 검증 (2026-11-05)

- UTF-16 기반 @Size를 @CodePointLength로 대체했다. name 필수·공백 처리와 deviceType 선택 정책은 유지한다.
- 생성된 /v3/api-docs에서 OpenAPI 3.1 요청 deviceType의 string/null 타입과 required 제외를 검증했다.
  응답 deviceType도 string/null이며, 실제 null 응답 필드의 존재까지 확인했다.
- 차량 HTTP 테스트 11개 통과. 일반 문자·보충 문자·혼합 문자열의 name 100/101자,
  deviceType 50/51자 경계와 기존 필수값·공백·MAC 검증을 포함한다.
- 일반 테스트 전체 59개 통과, 실패/오류/skip 0. DB 관련 코드 변경이 없어 DB 회귀는 반복하지 않았다.
