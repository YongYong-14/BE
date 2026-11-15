package io.github.yongyong14.parking_server.map;

import java.util.*;
import org.springframework.context.annotation.*;
import org.springdoc.core.customizers.OpenApiCustomizer;
import io.swagger.v3.core.converter.*;
import io.swagger.v3.oas.models.media.*;

@Configuration
public class MapOpenApiConfig {
    @Bean OpenApiCustomizer mapContract() {
        return api -> {
            var resolved=ModelConverters.getInstance(true).resolveAsResolvedSchema(
                    new AnnotatedType(MapRiskResponse.class).resolveAsRef(true));
            resolved.referencedSchemas.forEach(api.getComponents()::addSchemas);
            for(String name:List.of("MapRiskResponse","FacilityZone","Cell","Location","Slot","DataPeriod")) {
                var schema=api.getComponents().getSchemas().get(name);
                schema.setRequired(new ArrayList<>(schema.getProperties().keySet()));
            }
            var nullable=new Schema<Object>(); nullable.setTypes(Set.of("null"));
            api.getComponents().getSchemas().get("MapRiskResponse").addProperty("dataPeriod",
                    new ComposedSchema().addOneOfItem(new Schema<>().$ref("#/components/schemas/DataPeriod"))
                            .addOneOfItem(nullable));
            var success=new ObjectSchema().addProperty("success",new BooleanSchema()._const(true))
                    .addProperty("data",resolved.schema).addProperty("error",nullable);
            success.setRequired(List.of("success","data","error"));
            api.getComponents().addSchemas("MapRiskSuccess",success);
        };
    }
}
