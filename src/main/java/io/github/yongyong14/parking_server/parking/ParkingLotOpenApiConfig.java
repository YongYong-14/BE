package io.github.yongyong14.parking_server.parking;

import java.util.*;
import org.springframework.context.annotation.*;
import org.springdoc.core.customizers.OpenApiCustomizer;
import io.swagger.v3.oas.models.media.*;

@Configuration
public class ParkingLotOpenApiConfig {
    @Bean OpenApiCustomizer parkingLotContract() {
        return api -> {
            var location=new ObjectSchema().addProperty("lat",new NumberSchema()).addProperty("lng",new NumberSchema());
            location.setRequired(List.of("lat","lng"));
            var item=new ObjectSchema().addProperty("id",new StringSchema().format("uuid"))
                    .addProperty("name",new StringSchema()).addProperty("address",nullable("string"))
                    .addProperty("distanceM",new NumberSchema().description("ST_Distance 직선 거리(m)"))
                    .addProperty("location",location).addProperty("parkingType",nullable("string"))
                    .addProperty("feeType",nullable("string")._enum(Arrays.asList("FREE","PAID","MIXED",null)))
                    .addProperty("feeInfo",nullable("string"))
                    .addProperty("operatingHours",nullable("string").description("KST 조회일. 공휴일 우선, 일요일은 공휴일 칸(명세 #13 임시). HH:mm-HH:mm; 24시간 00:00-23:59, 종료가 빠르면 익일. 결측은 휴무가 아닌 null."))
                    .addProperty("capacity",nullable("integer"));
            item.setRequired(new ArrayList<>(item.getProperties().keySet()));
            var data=new ObjectSchema().addProperty("radiusM",new IntegerSchema()).addProperty("expanded",new BooleanSchema())
                    .addProperty("items",new ArraySchema().items(item));
            data.setRequired(List.of("radiusM","expanded","items"));
            api.getComponents().addSchemas("ParkingLotData",data);
            var success=new ObjectSchema().addProperty("success",new BooleanSchema()._const(true))
                    .addProperty("data",new Schema<>().$ref("#/components/schemas/ParkingLotData"))
                    .addProperty("error",new Schema<>().types(Set.of("null")));
            success.setRequired(List.of("success","data","error"));
            api.getComponents().addSchemas("ParkingLotSuccess",success);
        };
    }
    private static Schema<Object> nullable(String type) { return new Schema<>().types(Set.of(type,"null")); }
}
