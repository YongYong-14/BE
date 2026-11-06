package io.github.yongyong14.parking_server.vehicle;

import io.swagger.v3.oas.models.media.*;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.*;

@Configuration
public class VehicleOpenApiConfig {
    @Bean OpenApiCustomizer vehicleSchemas() {
        return api -> {
            var details = new ObjectSchema()
                    .addProperty("id", new StringSchema().format("uuid"))
                    .addProperty("name", new StringSchema().maxLength(100))
                    .addProperty("bluetoothAddress", new StringSchema().pattern("^([0-9A-F]{2}:){5}[0-9A-F]{2}$"))
                    .addProperty("deviceType", new Schema<>().types(Set.of("string", "null")).maxLength(50));
            details.setRequired(List.of("id", "name", "bluetoothAddress", "deviceType"));
            api.getComponents().addSchemas("VehicleDetails", details);
            var data = new ObjectSchema().addProperty("vehicle", new ComposedSchema().oneOf(List.of(
                    new Schema<>().$ref("#/components/schemas/VehicleDetails"), new Schema<>().types(Set.of("null")))));
            data.setRequired(List.of("vehicle"));
            var success = new ObjectSchema().addProperty("success", new BooleanSchema()._const(true))
                    .addProperty("data", data).addProperty("error", new Schema<>().types(Set.of("null")));
            success.setRequired(List.of("success", "data", "error"));
            api.getComponents().addSchemas("VehicleSuccess", success);
            var changed = new ObjectSchema().addProperty("vehicle", new Schema<>().$ref("#/components/schemas/VehicleDetails"));
            changed.setRequired(List.of("vehicle"));
            var putSuccess = new ObjectSchema().addProperty("success", new BooleanSchema()._const(true))
                    .addProperty("data", changed).addProperty("error", new Schema<>().types(Set.of("null")));
            putSuccess.setRequired(List.of("success", "data", "error"));
            api.getComponents().addSchemas("VehiclePutSuccess", putSuccess);
        };
    }
}
