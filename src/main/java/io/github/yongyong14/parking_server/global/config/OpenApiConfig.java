package io.github.yongyong14.parking_server.global.config;

import io.github.yongyong14.parking_server.global.exception.ErrorCode;
import io.github.yongyong14.parking_server.global.response.ApiResponse;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.*;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI openAPI() {
        var components = new Components();
        var error = new ObjectSchema()
                .addProperty("code", new StringSchema())
                .addProperty("message", new StringSchema());
        error.setRequired(List.of("code", "message"));
        components.addSchemas("ErrorResponse", error);
        var failure = new ObjectSchema()
                .addProperty("success", new BooleanSchema()._const(false))
                .addProperty("data", nullSchema())
                .addProperty("error", new Schema<>().$ref("#/components/schemas/ErrorResponse"));
        failure.setRequired(List.of("success", "data", "error"));
        components.addSchemas("CommonFailure", failure);
        for (ErrorCode code : ErrorCode.values()) {
            components.addResponses(code.getCode(), new io.swagger.v3.oas.models.responses.ApiResponse()
                    .description(code.getStatus().value() + " " + code.getMessage())
                    .content(new Content().addMediaType("application/json", new MediaType()
                            .schema(new Schema<>().$ref("#/components/schemas/CommonFailure"))
                            .example(ApiResponse.failure(code.getCode(), code.getMessage())))));
        }
        return new OpenAPI().info(new Info().title("용인 주차 안내 서비스 API")
                .description("주정차 주의 구역 및 대안 주차장 안내 서비스의 백엔드 API\n\n"
                        + "JSON 성공 응답은 ApiResponse.success(data)를 사용합니다. "
                        + "201 등 상태 지정은 ResponseEntity를 사용하고, 204는 본문 없이 반환합니다. "
                        + "MVC 오류는 실제 HTTP 상태와 표준 헤더를 보존합니다. "
                        + "미매핑 상태는 5xx이면 INTERNAL_SERVER_ERROR, 그 외에는 REQUEST_REJECTED입니다.")
                .version("v1")).components(components);
    }

    @Bean
    OpenApiCustomizer commonSuccessSchema() {
        return api -> api.getComponents().getSchemas().values().stream()
                .filter(schema -> schema.getProperties() != null
                        && schema.getProperties().keySet().containsAll(List.of("success", "data", "error"))
                        && schema != api.getComponents().getSchemas().get("CommonFailure"))
                .forEach(schema -> {
                    schema.setRequired(List.of("success", "data", "error"));
                    schema.addProperty("success", new BooleanSchema()._const(true));
                    Schema<?> data = (Schema<?>) schema.getProperties().get("data");
                    schema.addProperty("data", new ComposedSchema().anyOf(List.of(data, nullSchema())));
                    schema.addProperty("error", nullSchema());
                });
    }

    private static Schema<Object> nullSchema() {
        var schema = new Schema<Object>();
        schema.setTypes(java.util.Set.of("null"));
        return schema;
    }
}
