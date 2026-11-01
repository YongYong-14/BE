package io.github.yongyong14.parking_server.global.health;

import io.github.yongyong14.parking_server.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    @GetMapping("/api/v1/health")
    public ApiResponse<HealthResponse> health() {
        return ApiResponse.success(new HealthResponse("UP"));
    }

    public record HealthResponse(String status) {
    }
}
