package io.github.yongyong14.parking_server.map;

import java.time.*;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.*;
import io.github.yongyong14.parking_server.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

@RestController
@RequestMapping("/api/v1/map/risk-zones")
public class MapRiskController {
    private final MapRiskService service;
    public MapRiskController(@Lazy MapRiskService service) { this.service=service; }
    @GetMapping
    @Operation(summary="지도 위험 구역 조회", description="WGS84 화면 범위(Point/LineString/Polygon). 원래 요청 화면의 각 변 2km 이하. facilityZones는 시설 중심이 화면 밖이어도 선택한 공개 규칙의 legal_distance_m 반경이 화면에 닿거나 겹치면 포함하며 radiusM으로 반환합니다. at은 시간대가 있는 ISO 8601이며 생략 시 서버 Clock 사용. 위치 동의와 이용 로그 없음. 예측 없는 칸은 안전을 의미하지 않습니다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",description="시설·주의 격자, 실제 조회 slot, 학습 시작~관측 종료 dataPeriod(start/end). 활성 버전 없으면 cells=[], dataPeriod=null",
            content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json",schema=@io.swagger.v3.oas.annotations.media.Schema(ref="#/components/schemas/MapRiskSuccess"),
                examples=@io.swagger.v3.oas.annotations.media.ExampleObject(value="""
                {"success":true,"data":{"facilityZones":[{"zoneType":"HYDRANT","location":{"lat":37.3213,"lng":127.0961},"radiusM":5}],"cells":[{"location":{"lat":37.3219,"lng":127.0972},"sizeM":50,"topPercent":8.0,"reportCount":12}],"slot":{"weekday":6,"timeSlot":6},"dataPeriod":{"start":"2025-01-01","end":"2025-09-25"}},"error":null}
                """))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="400",ref="#/components/responses/INVALID_INPUT"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401",ref="#/components/responses/UNAUTHORIZED"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="503",ref="#/components/responses/SERVICE_UNAVAILABLE"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="500",ref="#/components/responses/INTERNAL_SERVER_ERROR")})
    public ApiResponse<MapRiskResponse> get(@RequestParam double minLat,@RequestParam double minLng,
            @RequestParam double maxLat,@RequestParam double maxLng,
            @RequestParam(required=false) OffsetDateTime at) {
        return ApiResponse.success(service.get(new MapBounds(minLat,minLng,maxLat,maxLng),at));
    }
}
