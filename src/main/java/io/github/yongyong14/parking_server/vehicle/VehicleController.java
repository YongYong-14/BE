package io.github.yongyong14.parking_server.vehicle;

import io.github.yongyong14.parking_server.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/vehicle")
public class VehicleController {
    private final VehicleService service;
    public VehicleController(VehicleService service) { this.service = service; }
    @GetMapping
    @Operation(summary = "내 활성 차량 조회", description = "차량이 없으면 data.vehicle=null, HTTP 200")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "활성 차량 또는 null", content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json", schema = @io.swagger.v3.oas.annotations.media.Schema(ref = "#/components/schemas/VehicleSuccess"))),
            @ApiResponse(responseCode = "401", ref = "#/components/responses/UNAUTHORIZED"),
            @ApiResponse(responseCode = "500", ref = "#/components/responses/INTERNAL_SERVER_ERROR")})
    public io.github.yongyong14.parking_server.global.response.ApiResponse<VehicleResponse> get(@CurrentUser UUID userId) {
        return io.github.yongyong14.parking_server.global.response.ApiResponse.success(service.get(userId));
    }
    @PutMapping
    @Operation(summary = "차량 등록·변경", description = "백엔드 기본안: 등록·수정·교체 모두 HTTP 200, GET과 동일한 data.vehicle 반환. 동일 MAC은 ID 유지, 다른 MAC은 기존 행 소프트 삭제 후 새 행 생성. 동시 변경 충돌 시 재조회 후 재시도.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "백엔드 기본안: 변경 후 활성 차량", content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json", schema = @io.swagger.v3.oas.annotations.media.Schema(ref = "#/components/schemas/VehiclePutSuccess"))),
            @ApiResponse(responseCode = "400", description = "INVALID_INPUT 또는 INVALID_BLUETOOTH_ADDRESS", content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json", schema = @io.swagger.v3.oas.annotations.media.Schema(ref = "#/components/schemas/CommonFailure"))),
            @ApiResponse(responseCode = "401", ref = "#/components/responses/UNAUTHORIZED"),
            @ApiResponse(responseCode = "409", ref = "#/components/responses/VEHICLE_CONFLICT"),
            @ApiResponse(responseCode = "500", ref = "#/components/responses/INTERNAL_SERVER_ERROR")})
    public io.github.yongyong14.parking_server.global.response.ApiResponse<VehicleResponse> put(@CurrentUser UUID userId, @Valid @RequestBody VehicleRequest request) {
        return io.github.yongyong14.parking_server.global.response.ApiResponse.success(service.put(userId, request));
    }
}
