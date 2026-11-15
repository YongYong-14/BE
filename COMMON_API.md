# 공통 HTTP 응답 사용 규칙

Spring MVC 컨트롤러와 MVC 예외 처리기는 아래 응답 계약을 사용한다. Security 필터·인증 진입점·권한 처리기는 MVC 처리기를 거치지 않으므로 `SecurityErrorWriter`에서 같은 `ApiResponse.failure` JSON을 작성한다. 메서드 권한 예외는 MVC 처리기에서 FORBIDDEN으로 매핑한다. 연결 방법은 [인증 가이드](docs/AUTHENTICATION.md)를 따른다.

## 성공 응답

JSON 본문이 있는 성공 응답은 `ApiResponse.success(data)`를 사용한다. `success`, `data`, `error`는 항상 포함하며 `error`는 null이다.

201처럼 상태나 헤더 지정이 필요하면 `ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(data))`를 사용한다. 204는 `ResponseEntity.noContent().build()`로 **본문 없이** 반환한다. 204에 공통 JSON을 넣지 않는다.

`GET /api/v1/health`는 애플리케이션의 기본 HTTP 응답 확인용이다. DB나 외부 API를 호출하지 않으며 해당 의존성의 정상 상태를 보장하지 않는다.

## 실패 응답

`ApiResponse.failure(code, message)`가 `error` 객체를 직접 포함한다. 다시 감싸지 않는다. 외부 오류 코드는 `ErrorCode`의 명시적 문자열이며 enum 상수 이름을 변경하더라도 기존 문자열은 유지한다.

`ErrorCode.status`는 BusinessException에 적용되는 기본 HTTP 상태다. MVC 표준 예외에서는 프레임워크가 정한 실제 상태와 표준 헤더(Allow, Retry-After 등)를 보존하고 코드와 안전한 기본 메시지만 매핑한다. Content-Type은 공통 오류 JSON에 맞춰 application/json으로 설정한다.

| HTTP 상태 | 외부 코드 |
| --- | --- |
| 400 | INVALID_INPUT |
| 401 | UNAUTHORIZED |
| 401 | TOKEN_EXPIRED (액세스 토큰 만료) |
| 403 | FORBIDDEN |
| 404 | NOT_FOUND |
| 405 | METHOD_NOT_ALLOWED |
| 406 | NOT_ACCEPTABLE |
| 409 | CONFLICT |
| 413 | PAYLOAD_TOO_LARGE |
| 415 | UNSUPPORTED_MEDIA_TYPE |
| 500 | INTERNAL_SERVER_ERROR |
| 503 | SERVICE_UNAVAILABLE |

매핑되지 않은 MVC 상태는 5xx이면 INTERNAL_SERVER_ERROR, 그 외에는 REQUEST_REJECTED로 분류한다. 이때 기본 상태 500/400으로 덮어쓰지 않는다. 예를 들어 429는 REQUEST_REJECTED와 HTTP 429, 502는 INTERNAL_SERVER_ERROR와 HTTP 502를 반환한다.

## 검증 및 로깅

새 컨트롤러에서는 MVC 내장 메서드 검증을 우선 사용한다. HandlerMethodValidationException의 입력 실패는 400, 반환값 실패는 500을 유지한다.

클래스에 @Validated를 붙인 컨트롤러의 AOP 검증은 ConstraintViolationException을 발생시킬 수 있다. 현재 요청 핸들러와 동일한 클래스·메서드 시그니처의 명시적 HTTP 바인딩 파라미터 위반만 모두 확인되는 경우 400으로 분류한다. 반환값, 서비스·엔티티 검증, 혼합된 위반, 빈 위반 집합, 출처를 식별할 수 없는 위반은 500으로 처리한다. 암시적 바인딩이나 교차 파라미터 제약도 자동으로 클라이언트 오류라 단정하지 않는다.

BusinessException의 사용자 지정 메시지는 공개 가능한 문구만 전달한다. 내부 원인은 cause 생성자로 전달하고 메시지에 연결하지 않는다. 예상하지 못한 예외 및 검증 오류는 안전한 기본 메시지만 응답한다.

실제 응답 상태가 5xx이면 원인 체인을 포함한 예외 스택을 ERROR로 기록한다. 4xx는 상태·코드·예외 유형만 DEBUG로 기록한다. 요청 본문, URL/쿼리, 비밀번호, 토큰, 거절된 입력값, 검증 메시지는 진단 로그에 추가하지 않는다.
