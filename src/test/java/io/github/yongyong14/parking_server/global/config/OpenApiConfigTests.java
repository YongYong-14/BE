package io.github.yongyong14.parking_server.global.config;

import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTests {
    @Test
    void customizationIsIdempotentAndDoesNotChangeOtherContracts() throws Exception {
        var config = new OpenApiConfig();
        var api = config.openAPI();
        var nullable = new Schema<>().types(java.util.Set.of("null"));
        var unrelated = new ObjectSchema()
                .addProperty("success", new BooleanSchema())
                .addProperty("data", new ComposedSchema().anyOf(List.of(new StringSchema(), nullable)))
                .addProperty("error", new StringSchema());
        // A DTO with identical property names and another nullable success contract
        // must both remain untouched, regardless of their component names.
        api.getComponents().addSchemas("UnrelatedDto", unrelated);
        var emptySuccess = new ObjectSchema()
                .addProperty("success", new BooleanSchema()._const(true))
                .addProperty("data", nullable)
                .addProperty("error", nullable);
        api.getComponents().addSchemas("OtherSuccess", emptySuccess);
        var json = Json31.mapper();
        String unrelatedBefore = json.writeValueAsString(unrelated);
        String emptyBefore = json.writeValueAsString(emptySuccess);
        String failureBefore = json.writeValueAsString(api.getComponents().getSchemas().get("CommonFailure"));
        var customizer = config.healthSuccessSchema();
        customizer.customise(api);
        String first = json.writeValueAsString(api);
        customizer.customise(api);
        assertThat(json.writeValueAsString(api)).isEqualTo(first);
        assertThat(json.writeValueAsString(api.getComponents().getSchemas().get("UnrelatedDto")))
                .isEqualTo(unrelatedBefore);
        assertThat(json.writeValueAsString(api.getComponents().getSchemas().get("OtherSuccess")))
                .isEqualTo(emptyBefore);
        assertThat(json.writeValueAsString(api.getComponents().getSchemas().get("CommonFailure")))
                .isEqualTo(failureBefore);
    }
}
