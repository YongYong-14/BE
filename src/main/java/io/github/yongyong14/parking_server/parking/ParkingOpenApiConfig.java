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
            var delivery=new ObjectSchema().addProperty("deliveredAt",nullable("string").format("date-time")
                    .description("Instant 변환 후 마이크로초 절삭. confirmedAt 이상, 서버 현재 시각 + 미래 허용 오차(기본 2분) 이하. 만료 후 보고 허용."))
                    .addProperty("failure",new Schema<>().types(Set.of("string","null"))._enum(Arrays.asList("NO_PERMISSION","DISPLAY_FAILED","UNKNOWN",null))
                            .description("백엔드 기본안. 명세 v0.10은 NO_PERMISSION 등으로만 명시."));
            delivery.setAdditionalProperties(false);
            delivery.setDescription("둘 중 정확히 하나만 non-null. 생략은 null과 같음. 중복도 입력 검증. 최초 실패 유지, 성공 후 모든 유효한 보고는 최초 성공 유지. 실패 후 성공은 failure 제거.");
            delivery.setOneOf(List.of(new ObjectSchema().addProperty("deliveredAt",new StringSchema().format("date-time"))
                            .addProperty("failure",new Schema<>().types(Set.of("null"))).required(List.of("deliveredAt")),
                    new ObjectSchema().addProperty("deliveredAt",new Schema<>().types(Set.of("null")))
                            .addProperty("failure",enumeration("NO_PERMISSION","DISPLAY_FAILED","UNKNOWN")).required(List.of("failure"))));
            components.addSchemas("ParkingDeliveryRequest",delivery);
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
            var event=new ObjectSchema().addProperty("id",new StringSchema().format("uuid"))
                    .addProperty("detectedAt",new StringSchema().format("date-time"))
                    .addProperty("confirmedLocation",ref("ParkingPoint")).addProperty("address",nullable("string"))
                    .addProperty("vehicleName",new StringSchema());
            allRequired(event); components.addSchemas("ParkingQueryEvent",event);
            var period=new ObjectSchema().addProperty("start",new StringSchema().format("date"))
                    .addProperty("end",new StringSchema().format("date"));
            allRequired(period); components.addSchemas("ParkingDataPeriod",period);
            var summary=new ObjectSchema().addProperty("status",(Schema<?>)assessment.getProperties().get("status"))
                    .addProperty("level",(Schema<?>)assessment.getProperties().get("level"))
                    .addProperty("predictionStatus",(Schema<?>)assessment.getProperties().get("predictionStatus"));
            allRequired(summary); components.addSchemas("ParkingSummary",summary);
            var latest=new ObjectSchema().addProperty("event",optionalRef("ParkingQueryEvent"))
                    .addProperty("assessment",optionalRef("ParkingSummary")).addProperty("dataPeriod",optionalRef("ParkingDataPeriod"));
            allRequired(latest); components.addSchemas("ParkingLatest",latest);
            var peak=new ObjectSchema().description("백엔드 기본안: 확정 좌표의 50m 칸, detectedAt의 KST 요일과 저장 판정의 공휴일 상태/참조 버전 정책. 저장 판정과 모델의 원천 일치 및 training_start~observed_end의 DATE 경계 정확 일치만 사용. 누락 슬롯 0, 최대 동점은 이른 슬롯. 검증 불가/집계 없음은 null; 활성 모델 대체 없음.").addProperty("weekday",new IntegerSchema().minimum(BigDecimal.ONE).maximum(BigDecimal.valueOf(7)))
                    .addProperty("timeSlot",new IntegerSchema().minimum(BigDecimal.ZERO).maximum(BigDecimal.valueOf(7)))
                    .addProperty("slotCounts",new ArraySchema().items(new IntegerSchema().format("int64").minimum(BigDecimal.ZERO)).minItems(8).maxItems(8));
            allRequired(peak); components.addSchemas("ParkingPeak",peak);
            var detailStats=new ObjectSchema(); detailStats.setProperties(new LinkedHashMap<>(stats.getProperties()));
            detailStats.addProperty("peak",optionalRef("ParkingPeak")); allRequired(detailStats); components.addSchemas("ParkingDetailStats",detailStats);
            var detailAssessment=new ObjectSchema(); detailAssessment.setProperties(new LinkedHashMap<>(assessment.getProperties()));
            detailAssessment.addProperty("stats",ref("ParkingDetailStats")); allRequired(detailAssessment); components.addSchemas("ParkingDetailAssessment",detailAssessment);
            var detail=new ObjectSchema().addProperty("event",ref("ParkingQueryEvent")).addProperty("assessment",ref("ParkingDetailAssessment"));
            allRequired(detail); components.addSchemas("ParkingDetail",detail);
            var listEvent=new ObjectSchema();
            for (String field:List.of("id","detectedAt","address")) listEvent.addProperty(field,(Schema<?>)event.getProperties().get(field));
            allRequired(listEvent); components.addSchemas("ParkingListEvent",listEvent);
            var listAssessment=new ObjectSchema();
            for (String field:List.of("status","level","predictionStatus","primaryZoneType","referenceFineAmount"))
                listAssessment.addProperty(field,(Schema<?>)assessment.getProperties().get(field));
            listAssessment.addProperty("distanceM",nullable("number")); listAssessment.addProperty("topPercent",nullable("number"));
            allRequired(listAssessment); components.addSchemas("ParkingListAssessment",listAssessment);
            var item=new ObjectSchema().addProperty("event",ref("ParkingListEvent")).addProperty("assessment",ref("ParkingListAssessment"));
            allRequired(item); components.addSchemas("ParkingListItem",item);
            var items=new ObjectSchema().addProperty("items",new ArraySchema().items(ref("ParkingListItem")));
            allRequired(items); components.addSchemas("ParkingItems",items);
            for (var entry:Map.of("/api/v1/parking-events/latest","ParkingLatest","/api/v1/parking-events/{id}","ParkingDetail","/api/v1/parking-events","ParkingItems").entrySet()) {
                var envelope=new ObjectSchema().addProperty("success",new BooleanSchema()._const(true))
                        .addProperty("data",ref(entry.getValue())).addProperty("error",new Schema<>().types(Set.of("null")));
                allRequired(envelope); components.addSchemas(entry.getValue()+"Success",envelope);
                api.getPaths().get(entry.getKey()).getGet().getResponses().get("200").setContent(new io.swagger.v3.oas.models.media.Content()
                        .addMediaType("application/json",new MediaType().schema(ref(entry.getValue()+"Success"))));
            }
        };
    }
    private static Schema<?> nullable(String type) { return new Schema<>().types(Set.of(type,"null")); }
    private static Schema<?> ref(String type) { return new Schema<>().$ref("#/components/schemas/"+type); }
    private static Schema<?> optionalRef(String type) { return new ComposedSchema().oneOf(List.of(ref(type),new Schema<>().types(Set.of("null")))); }
    private static StringSchema enumeration(String... values) { var schema=new StringSchema(); schema.setEnum(List.of(values)); return schema; }
    private static void allRequired(Schema<?> schema) { schema.setRequired(new ArrayList<>(schema.getProperties().keySet())); }
}
