package io.github.yongyong14.parking_server.parking;

import java.math.BigDecimal;
import java.util.*;
import io.swagger.v3.oas.models.media.*;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.*;

@Configuration
public class ParkingOpenApiConfig {
    @Bean OpenApiCustomizer parkingSchemas() {
        return api->{
            var components=api.getComponents();
            var point=new ObjectSchema().addProperty("lat",new NumberSchema().minimum(BigDecimal.valueOf(-90)).maximum(BigDecimal.valueOf(90)))
                    .addProperty("lng",new NumberSchema().minimum(BigDecimal.valueOf(-180)).maximum(BigDecimal.valueOf(180)));
            point.setRequired(List.of("lat","lng"));
            components.addSchemas("ParkingPoint",point);
            var gps=new ObjectSchema().addProperty("lat",(Schema<?>)point.getProperties().get("lat"))
                    .addProperty("lng",(Schema<?>)point.getProperties().get("lng")).addProperty("accuracyM",new NumberSchema().minimum(BigDecimal.ZERO));
            allRequired(gps); components.addSchemas("ParkingGps",gps);
            var last=new ObjectSchema().addProperty("lat",(Schema<?>)point.getProperties().get("lat"))
                    .addProperty("lng",(Schema<?>)point.getProperties().get("lng")).addProperty("at",new StringSchema().format("date-time"));
            allRequired(last); components.addSchemas("ParkingLastKnown",last);
            var request=new ObjectSchema().addProperty("clientEventId",new StringSchema().format("uuid"))
                    .addProperty("vehicleId",new StringSchema().format("uuid"))
                    .addProperty("detectedAt",new StringSchema().format("date-time").description("시간대 필수. 신규 요청은 서버 시각 +2분까지 허용"))
                    .addProperty("confirmedAt",new StringSchema().format("date-time").description("detectedAt 이상"))
                    .addProperty("confirmationMethod",enumeration("CONFIRMED","ADJUSTED","AUTO"))
                    .addProperty("originalLocation",optionalRef("ParkingGps"))
                    .addProperty("lastKnownLocation",optionalRef("ParkingLastKnown").description("at 필수, detectedAt 이하·10분 이내"))
                    .addProperty("confirmedLocation",ref("ParkingPoint"))
                    .addProperty("remeasureCount",new IntegerSchema().minimum(BigDecimal.ZERO).maximum(BigDecimal.TEN));
            request.setRequired(List.of("clientEventId","vehicleId","detectedAt","confirmedAt","confirmationMethod","confirmedLocation","remeasureCount"));
            request.setAdditionalProperties(false);
            request.setDescription("AUTO는 originalLocation이 있어야 합니다. originalLocation 우선, 없으면 lastKnownLocation 기준 200m 이내 보정. 둘 다 없으면 수동 확정만 허용. 좌표 소수 7자리·동일 순간 정규화로 멱등 비교. GPS 정확도 등급 정책은 미결입니다.");
            components.addSchemas("ParkingRequest",request);
            var reason=new ObjectSchema().addProperty("rank",new IntegerSchema()).addProperty("type",enumeration("FACILITY_RULE","REPORT_HISTORY","MODEL"))
                    .addProperty("zoneType",nullable("string")).addProperty("distanceM",nullable("number"))
                    .addProperty("thresholdM",nullable("number")).addProperty("accuracyMarginM",nullable("number"))
                    .addProperty("description",new StringSchema());
            allRequired(reason); components.addSchemas("ParkingReason",reason);
            var stats=new ObjectSchema().addProperty("reportCount",nullable("integer")).addProperty("reportRadiusM",nullable("integer"))
                    .addProperty("periodStart",nullable("string").format("date")).addProperty("periodEnd",nullable("string").format("date"))
                    .addProperty("predictedCount",nullable("number").description("선택 버전의 smoothed_count. 격자 중심 근사값"))
                    .addProperty("topPercent",nullable("number").description("신고 집중도 상위 비율. 단속 확률이 아니며 원시값으로 비교합니다"));
            allRequired(stats); components.addSchemas("ParkingStats",stats);
            var notify=new ObjectSchema().addProperty("decision",enumeration("SEND","SUPPRESSED_SETTING","SUPPRESSED_NIGHT","NONE"))
                    .addProperty("expiresAt",new StringSchema().format("date-time").description("저장된 detectedAt + 30분. 앱은 표시 시 만료를 확인합니다"))
                    .addProperty("title",nullable("string")).addProperty("body",nullable("string"));
            allRequired(notify); components.addSchemas("ParkingNotify",notify);
            var assessment=new ObjectSchema().addProperty("status",enumeration("SUCCEEDED","PARTIAL","FAILED"))
                    .addProperty("level",nullable("integer").description("SUCCEEDED만 1/2/3, PARTIAL·FAILED는 null"))
                    .addProperty("predictionStatus",nullable("string").description("OK/SKIPPED/NOT_TARGET/OUT_OF_AREA/NO_ACTIVE_VERSION/MISSING_ROW 또는 null"))
                    .addProperty("primaryZoneType",nullable("string").description("1단계 대표 시설 유형. 모델 2·3단계는 null (API v0.10)."))
                    .addProperty("reasons",new ArraySchema().items(ref("ParkingReason")))
                    .addProperty("stats",ref("ParkingStats"))
                    .addProperty("referenceFineAmount",nullable("integer").description("1단계는 대표 시설 규칙, 2단계는 선택한 공개 묶음의 OTHER.fine_amount_car 저장값. 3단계·PARTIAL·FAILED는 null. 기존 이벤트는 저장값 유지."))
                    .addProperty("attemptCount",new IntegerSchema().minimum(BigDecimal.ONE).maximum(BigDecimal.valueOf(2)))
                    .addProperty("notify",ref("ParkingNotify")).addProperty("evaluatedAt",nullable("string").format("date-time"));
            allRequired(assessment); components.addSchemas("ParkingAssessment",assessment);
            var data=new ObjectSchema().addProperty("parkingEventId",new StringSchema().format("uuid"))
                    .addProperty("address",nullable("string")).addProperty("assessment",ref("ParkingAssessment"));
            allRequired(data); components.addSchemas("ParkingResponse",data);
            var success=new ObjectSchema().addProperty("success",new BooleanSchema()._const(true))
                    .addProperty("data",ref("ParkingResponse")).addProperty("error",new Schema<>().types(Set.of("null")));
            allRequired(success); components.addSchemas("ParkingSuccess",success);
        };
    }
    private static Schema<?> nullable(String type) { return new Schema<>().types(Set.of(type,"null")); }
    private static Schema<?> ref(String type) { return new Schema<>().$ref("#/components/schemas/"+type); }
    private static Schema<?> optionalRef(String type) { return new ComposedSchema().oneOf(List.of(ref(type),new Schema<>().types(Set.of("null")))); }
    private static StringSchema enumeration(String... values) { var schema=new StringSchema(); schema.setEnum(List.of(values)); return schema; }
    private static void allRequired(Schema<?> schema) { schema.setRequired(new ArrayList<>(schema.getProperties().keySet())); }
}
