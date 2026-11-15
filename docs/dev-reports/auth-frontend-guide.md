# 앱에서 인증과 회원 API 연결하기

모든 경로는 `/api/v1` 아래에 있다. JSON 응답은 `success`, `data`, `error`를 사용한다. HTTP 204는 본문이 비어 있으므로 JSON 파싱을 생략한다. 아래 토큰은 설명용 자리표시자다.

## 로그인과 액세스 JWT

카카오 SDK가 앱에 발급한 **카카오 액세스 토큰**을 백엔드에 보낸다. `deviceInfo`는 선택값이며 최대 200자다.

```http
POST /api/v1/auth/kakao
Content-Type: application/json

{"kakaoAccessToken":"<카카오 액세스 토큰>","deviceInfo":"Android"}
```

```json
{
  "success": true,
  "data": {
    "accessToken": "<서비스 액세스 JWT>",
    "refreshToken": "<서비스 리프레시 토큰>",
    "expiresIn": 1800,
    "isNewUser": true,
    "user": {
      "id": "00000000-0000-4000-8000-000000000001",
      "nickname": "예시",
      "profileImageUrl": null
    },
    "agreements": {"missing": ["TERMS", "PRIVACY", "LOCATION_SERVICE"]}
  },
  "error": null
}
```

보호 API에는 `Authorization: Bearer <서비스 액세스 JWT>`를 붙인다. 예를 들어 `GET /api/v1/me`는 회원 UUID, 닉네임, 이메일, `hasVehicle`, 유형별 최신 약관 이력을 내려준다. 앱은 카카오 토큰과 서비스 토큰을 구분하고, 서비스 리프레시를 보호 API에 사용하지 않는다. 회원 UUID를 요청으로 보내서 사용자를 선택하는 방식은 지원하지 않는다.

카카오 토큰 오류는 `401 KAKAO_TOKEN_INVALID`, 일시적인 외부 의존성 장애나 서버 카카오 설정 누락은 `503 SERVICE_UNAVAILABLE`로 처리한다. 토큰 원문을 앱 로그, 분석 이벤트, 오류 수집에 넣지 않는다.

## 재발급과 로그아웃

- 서비스 액세스가 만료되어 `401 TOKEN_EXPIRED`를 받으면 `POST /api/v1/auth/refresh`에 `{"refreshToken":"<현재 서비스 리프레시 토큰>"}`을 보낸다.
- 앱 세션당 재발급 요청 하나만 실행한다. 동시에 만료 응답을 받은 요청은 같은 재발급 결과를 기다린 뒤 새로운 JWT로 한 번 재시도한다.
- 재발급 성공 응답의 액세스와 리프레시를 한 쌍으로 교체한다. 앱 종료나 로그아웃 이후 늦게 도착한 응답이 폐기한 세션을 복구하지 않도록 세션 식별자를 확인한다. 운영체제의 보호 저장소에 리프레시 토큰을 저장한다.
- 이전 리프레시를 재사용하면 해당 계열이 폐기되고 `401 TOKEN_REUSED`가 온다. 두 서비스 토큰을 지우고 재로그인을 안내한다. 타임아웃으로 재발급 응답을 잃은 경우 같은 리프레시를 무한 자동 재시도하면 재사용으로 판정될 수 있다.
- 로그아웃은 보호 경로 `POST /api/v1/auth/logout`에 현재 서비스 리프레시를 JSON 본문으로 전달한다. 성공 204 후 로컬 토큰을 지운다. 다른 로그인 계열은 유지된다. 현재 액세스 JWT는 서버에서 원래 만료 시각까지 유효하므로 앱이 폐기해야 한다.

## 탈퇴, 약관, 알림

- `DELETE /api/v1/me` 성공은 204다. 앱은 로그인 상태를 지운다. 서버는 카카오 연결 해제를 시도하고 서비스 계정을 삭제한다. 카카오에서 직접 연결을 해제했을 때의 웹훅은 카카오 서버 전용이다. 앱은 웹훅을 호출하거나 관리자 키를 저장하지 않는다.
- 약관 동의는 `POST /api/v1/me/agreements`에 예를 들어 `{"type":"TERMS","version":"<서버에서 허용한 버전>","action":"AGREE"}`를 보낸다. 성공은 201이다. 지원 타입은 `TERMS`, `PRIVACY`, `LOCATION_SERVICE`다. 현재 운영 버전은 앱과 백엔드가 배포 전에 합의한다. 위치 철회는 정책이 확정될 때까지 503이며 철회 완료 화면으로 전환할 수 없다.
- `GET /api/v1/notification-settings`와 아래 PATCH는 `dangerEnabled`, `cautionEnabled`, `nightEnabled`를 포함한 전체 설정을 반환한다. PATCH에는 바꿀 불리언만 넣는다. `dangerEnabled`, 알 수 없는 필드, `null`, 문자열 불리언은 400이다.

```http
PATCH /api/v1/notification-settings
Authorization: Bearer <서비스 액세스 JWT>
Content-Type: application/json

{"nightEnabled":true}
```
