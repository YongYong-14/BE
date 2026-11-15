package io.github.yongyong14.parking_server.parking;

import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.*;
import io.github.yongyong14.parking_server.global.security.CurrentUser;
import io.github.yongyong14.parking_server.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

@RestController
@RequestMapping("/api/v1/parking-lots/nearby")
public class ParkingLotController {
    private final ParkingLotService service;
    public ParkingLotController(@Lazy ParkingLotService service) { this.service=service; }
    @GetMapping
    @Operation(summary="주변 주차장 조회",description="현재 LOCATION_SERVICE 동의 필요. WGS84 좌표, 양수 반경 최대 1000m. 500m 조회가 비었을 때만 1000m로 확장. 직선 거리순이며 개수 제한 없음. KST 조회일의 공휴일·일요일/토요일/평일 운영시간 한 개. 결측은 null. USE 로그 기록.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",description="주차장 목록 또는 빈 배열",content=@Content(schema=@Schema(ref="#/components/schemas/ParkingLotSuccess"))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="400",ref="#/components/responses/INVALID_INPUT"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401",ref="#/components/responses/UNAUTHORIZED"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403",ref="#/components/responses/LOCATION_CONSENT_REQUIRED"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="500",ref="#/components/responses/INTERNAL_SERVER_ERROR")})
    public ApiResponse<ParkingLotResponse> nearby(@CurrentUser UUID user,
            @Parameter(schema=@Schema(minimum="-90",maximum="90")) @RequestParam double lat,
            @Parameter(schema=@Schema(minimum="-180",maximum="180")) @RequestParam double lng,
            @Parameter(schema=@Schema(type="integer",minimum="1",maximum="1000",defaultValue="500")) @RequestParam(defaultValue="500") int radius) {
        ParkingLotService.validate(lat,lng,radius);
        return ApiResponse.success(service.nearby(user,lat,lng,radius));
    }
}
