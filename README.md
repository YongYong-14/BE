# 용인 주차 안내 서비스 API

Java 21이 필요합니다. Spring Boot 4.1.2를 사용하며 DB 설정 없이 실행·테스트할 수 있습니다.
`JAVA_HOME`을 JDK 21 경로로 설정하세요.

```powershell
.\gradlew.bat clean build
.\gradlew.bat bootRun
```

기본 포트는 8080입니다.

배포 시 환경 변수 `SPRING_PROFILES_ACTIVE=prod`를 반드시 설정해 운영 프로필을 활성화하세요.
`application-prod.yaml`에서 OpenAPI 문서와 Swagger UI를 비활성화합니다.
프로필을 지정하지 않은 로컬 실행에서는 기존처럼 아래 문서를 사용할 수 있습니다.

- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs
- Swagger 설정: http://localhost:8080/v3/api-docs/swagger-config

Swagger UI에서 Health → GET → Try it out → Execute로 호출하거나 다음 명령을 사용합니다.

```powershell
curl.exe http://localhost:8080/api/v1/health
```

```json
{"success":true,"data":{"status":"UP"},"error":null}
```

health는 DB나 외부 API를 호출하지 않는 기본 HTTP 응답 확인용이며 외부 의존성의 정상 상태를 보장하지 않습니다.

## API 문서 작성

문서에는 루트 패키지의 `/api/v1/**` API만 포함합니다. 테스트 컨트롤러는 테스트 소스에 두고
서비스 경로를 사용하는 테스트 컨트롤러가 필요하면 `@Hidden`을 사용하세요.
문서 JSON과 Swagger 설정은 `ApiResponse`로 감싸지 않습니다.

컨트롤러에 `@Tag`, 메서드에 `@Operation`으로 목적과 동작을 기록합니다.
성공 반환 타입은 `ApiResponse<실제DTO>`로 선언하고, data의 null 허용 여부는 실제 API 동작을 기준으로 문서화합니다.
DTO의 항상 존재하는 필드는 `@Schema(requiredMode = Schema.RequiredMode.REQUIRED)`로 표시합니다.
성공 예시는 health 컨트롤러의 `@Content(examples = @ExampleObject(...))` 방식을 참고하세요.

OpenAPI 3.1을 사용합니다. health는 명시적인 `HealthSuccess` 스키마를 참조합니다.
success/data/error는 모두 required이며 success는 true, error는 `type: "null"`,
data는 실제 `HealthResponse` 객체로 null을 허용하지 않습니다.
커스터마이저는 이 명시적인 성공 계약만 구성하며 다른 공통 응답이나 DTO는 변경하지 않습니다.
모든 `ApiResponse<T>`에 null을 일괄 추가하거나 T가 Void가 아니라는 이유로 null 불가를 추정하지 않습니다.
데이터 없는 JSON 성공 응답은 실제로 null을 반환한다면 data를 `type: "null"`로 명시하고,
선택적으로 null을 반환하는 응답만 실제 DTO와 null의 `anyOf`로 표현하세요.
필수 필드 존재와 null 허용은 별개입니다. 공통 실패 `CommonFailure`는 success=false,
data=null, error는 필수 code/message 문자열을 갖는 `ErrorResponse`입니다.

`OpenApiConfig`는 `ErrorCode`에서 재사용 가능한 `components.responses` 정의와 기본 예시를 구성합니다.
실제로 발생하는 오류만 메서드에 다음과 같이 참조하세요.

```java
@io.swagger.v3.oas.annotations.responses.ApiResponse(
    responseCode = "400", ref = "#/components/responses/INVALID_INPUT")
```

health에는 메서드 거절(405), 응답 형식 거절(406), 예상하지 못한 서버 오류(500)를 명시합니다.
인증이 없는 API에 401/403을 추가하지 않습니다. ControllerAdvice 자동 응답 추가는
`springdoc.override-with-generic-response=false`로 끄고 각 API에서 오류를 선택합니다.
사용자 지정 오류 메시지가 있으면 해당 API의 예시도 실제 공개 메시지에 맞추세요.

201 등 상태·헤더 지정은 `ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(data))`를
사용하고 문서에도 해당 상태를 명시합니다. 204는 `ResponseEntity.noContent().build()`로 본문 없이
반환하고 `@ApiResponse(responseCode = "204", description = "처리 완료", content = @Content)`처럼
본문 스키마를 지정하지 않습니다.

HTTP 상태와 오류 코드의 상세 계약은 [COMMON_API.md](COMMON_API.md)를 따릅니다.
MVC 오류는 실제 상태와 표준 헤더를 보존합니다. 매핑되지 않은 상태는 5xx이면
INTERNAL_SERVER_ERROR, 그 외에는 REQUEST_REJECTED로 분류합니다.

springdoc은 정식 버전 **3.2.0**을 사용합니다.
[공식 호환표](https://springdoc.org/faq.html#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot)는
Boot 4.1.x와 springdoc 3.1.x–3.2.x 호환을 명시하며,
[3.2.0 릴리스](https://github.com/springdoc/springdoc-openapi/releases/tag/v3.2.0)는 Boot 4.1.2 기반입니다.

`OpenApiTests`는 전체 Spring 설정으로 생성한 문서의 응답 스키마·예시, 서비스 경로 필터,
Swagger 설정 및 정적 리소스를 검증합니다. 브라우저의 화면 렌더링과 Try it out 클릭 동작은 별도 수동 확인 대상입니다.
