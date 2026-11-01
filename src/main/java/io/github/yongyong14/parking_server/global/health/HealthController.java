package io.github.yongyong14.parking_server.global.health;

import io.github.yongyong14.parking_server.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Health")
public class HealthController {
    @Operation(summary = "서버 기본 응답 확인", description = "DB나 외부 API를 호출하지 않는 기본 HTTP 응답 확인용이며, 외부 의존성의 정상 상태를 보장하지 않음")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "기본 HTTP 응답 성공",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/HealthSuccess"), examples = @ExampleObject(value = """
                {"success":true,"data":{"status":"UP"},"error":null}
                """))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "405", ref = "#/components/responses/METHOD_NOT_ALLOWED"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "406", ref = "#/components/responses/NOT_ACCEPTABLE"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", ref = "#/components/responses/INTERNAL_SERVER_ERROR")
    })
    @GetMapping("/api/v1/health")
    public ApiResponse<HealthResponse> health() {
        return ApiResponse.success(new HealthResponse("UP"));
    }

    public record HealthResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "UP") String status) {
    }
}
