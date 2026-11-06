package io.github.yongyong14.parking_server.parking;

import java.util.UUID;
import io.github.yongyong14.parking_server.global.security.CurrentUser;
import io.github.yongyong14.parking_server.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/parking-events")
public class ParkingController {
    private final ParkingService service;
    public ParkingController(ParkingService service) { this.service=service; }
    @PostMapping
    @Operation(summary="주차 저장 및 위험 판정",description="신규 201, 기존 키 처리 200. 현재 위치 동의 확인 후 멱등 처리. SUCCEEDED/PARTIAL은 저장된 판정·알림을 반환하고 FAILED만 최대 2회 시도합니다. 판정 FAILED도 success=true입니다. 규칙층이 모델보다 우선하며 PARTIAL은 안전 판정이 아닙니다. notify.expiresAt=detectedAt+30분; 저장된 SEND여도 앱은 실제 표시 시 만료·권한·중복을 확인해야 합니다. 주소는 커밋 후 비동기로 갱신되며 최초 응답은 null일 수 있습니다.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="application/json",schema=@Schema(ref="#/components/schemas/ParkingRequest")))
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="201",description="신규 이벤트 및 판정 (FAILED 포함)",content=@Content(mediaType="application/json",schema=@Schema(ref="#/components/schemas/ParkingSuccess"))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",description="기존 판정 반환 또는 FAILED 1회 재판정",content=@Content(mediaType="application/json",schema=@Schema(ref="#/components/schemas/ParkingSuccess"))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="400",description="INVALID_INPUT, INVALID_TIME, INVALID_TIME_ORDER, AUTO_CONFIRM_NOT_ALLOWED, ADJUST_DISTANCE_EXCEEDED",content=@Content(mediaType="application/json",schema=@Schema(ref="#/components/schemas/CommonFailure"))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401",ref="#/components/responses/UNAUTHORIZED"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403",ref="#/components/responses/LOCATION_CONSENT_REQUIRED"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="404",ref="#/components/responses/VEHICLE_NOT_FOUND"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409",ref="#/components/responses/IDEMPOTENCY_KEY_REUSED"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="500",ref="#/components/responses/INTERNAL_SERVER_ERROR")})
    public ResponseEntity<ApiResponse<ParkingResponse>> save(@CurrentUser UUID user,@RequestBody ParkingRequest request) {
        var saved=service.save(user,request);
        return ResponseEntity.status(saved.created() ? 201 : 200).body(ApiResponse.success(saved.response()));
    }
}
