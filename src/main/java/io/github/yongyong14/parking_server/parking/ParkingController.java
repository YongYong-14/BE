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
    private final ParkingQueryService queries;
    public ParkingController(ParkingService service, ParkingQueryService queries) { this.service=service; this.queries=queries; }
    @PatchMapping("/{id}/delivery")
    @Operation(summary="알림 표시 결과 보고",description="deliveredAt/failure 중 정확히 하나. SEND만 보고 가능. 최초 실패 보존, 실패 후 성공은 성공으로 전환, 최초 성공은 유지. 시각 검증은 중복에도 적용. 위치 동의 검사·이용 로그 없음.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(schema=@Schema(ref="#/components/schemas/ParkingDeliveryRequest")))
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="204",description="보고 처리 완료. 본문 없음",content=@Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="400",description="INVALID_INPUT: UUID, 배타 입력, 실패 사유 또는 시각 오류",content=@Content(schema=@Schema(ref="#/components/schemas/CommonFailure"))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401",ref="#/components/responses/UNAUTHORIZED"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="404",description="PARKING_EVENT_NOT_FOUND",content=@Content(schema=@Schema(ref="#/components/schemas/CommonFailure"))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409",description="DELIVERY_NOT_EXPECTED: 저장된 결정이 SEND가 아님",content=@Content(schema=@Schema(ref="#/components/schemas/CommonFailure"))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="500",ref="#/components/responses/INTERNAL_SERVER_ERROR")})
    public ResponseEntity<Void> delivery(@CurrentUser UUID user,@PathVariable UUID id,@RequestBody ParkingDeliveryRequest request) {
        queries.delivery(user,id,request);
        return ResponseEntity.noContent().build();
    }
    @GetMapping("/latest")
    @Operation(summary="최근 주차 조회",description="detectedAt DESC, id DESC. 기록이 없으면 event와 assessment는 null. dataPeriod는 현재 활성 예측 버전의 training_start~observed_end이며 활성 버전이 없으면 null. 위치 동의 검사·이용 로그 없음.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",description="최근 기록 또는 빈 상태"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401",ref="#/components/responses/UNAUTHORIZED"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="500",ref="#/components/responses/INTERNAL_SERVER_ERROR")})
    public ApiResponse<ParkingQueryResponse.Latest> latest(@CurrentUser UUID user) { return ApiResponse.success(queries.latest(user)); }
    @GetMapping("/{id}")
    @Operation(summary="주차 기록 상세",description="본인 기록과 저장된 assessment. stats.peak는 상세 전용. 위치 동의 검사·이용 로그 없음.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",description="본인 주차 기록"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="400",description="INVALID_INPUT: 잘못된 UUID",content=@Content(schema=@Schema(ref="#/components/schemas/CommonFailure"))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401",ref="#/components/responses/UNAUTHORIZED"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="404",description="PARKING_EVENT_NOT_FOUND: 없거나 타인 기록",content=@Content(schema=@Schema(ref="#/components/schemas/CommonFailure"))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="500",ref="#/components/responses/INTERNAL_SERVER_ERROR")})
    public ApiResponse<ParkingQueryResponse.Detail> detail(@CurrentUser UUID user,@PathVariable UUID id) { return ApiResponse.success(queries.detail(user,id)); }
    @GetMapping
    @Operation(summary="주차 기록 목록",description="본인 기록 detectedAt DESC, id DESC. 빈 목록은 items: []. ALL은 PARTIAL·FAILED 포함. 위치 동의 검사·이용 로그 없음.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",description="목록 또는 빈 배열"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="400",description="INVALID_INPUT",content=@Content(schema=@Schema(ref="#/components/schemas/CommonFailure"))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401",ref="#/components/responses/UNAUTHORIZED"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="500",ref="#/components/responses/INTERNAL_SERVER_ERROR")})
    public ApiResponse<ParkingQueryResponse.Items> list(@CurrentUser UUID user,
            @io.swagger.v3.oas.annotations.Parameter(schema=@Schema(allowableValues={"ALL","1","2"},defaultValue="ALL")) @RequestParam(required=false) String level,
            @io.swagger.v3.oas.annotations.Parameter(schema=@Schema(type="integer",minimum="1",maximum="100",defaultValue="50")) @RequestParam(required=false) String limit) {
        int count=50;
        if (limit!=null) {
            try {
                if (!limit.matches("[0-9]+")) throw new NumberFormatException();
                count=Integer.parseInt(limit);
            } catch (NumberFormatException e) {
                throw new io.github.yongyong14.parking_server.global.exception.BusinessException(io.github.yongyong14.parking_server.global.exception.ErrorCode.INVALID_INPUT);
            }
        }
        return ApiResponse.success(queries.list(user,level==null ? "ALL" : level,count));
    }
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
