package io.github.yongyong14.parking_server.parking;

import java.time.*;
import java.util.*;

/** Only explicitly owned public fields. JDBC/internal error and lineage fields never leak to the response. */
public record ParkingResponse(UUID parkingEventId, String address, Assessment assessment) {
    public record Assessment(String status, Integer level, String predictionStatus, String primaryZoneType,
            List<Reason> reasons, Stats stats, Integer referenceFineAmount, int attemptCount,
            @com.fasterxml.jackson.annotation.JsonProperty("notify") Notification notification, OffsetDateTime evaluatedAt) {}
    public record Reason(int rank, String type, String zoneType, Double distanceM, Double thresholdM,
            Double accuracyMarginM, String description) {}
    public record Stats(Integer reportCount, Integer reportRadiusM, LocalDate periodStart, LocalDate periodEnd,
            Double predictedCount, Double topPercent) {}
    public record Notification(String decision, OffsetDateTime expiresAt, String title, String body) {}

    static ParkingResponse from(ParkingTransactions.Result result) {
        var a=result.assessment();
        var stats=new Stats(integer(a.get("report_count")),integer(a.get("report_count_radius_m")),
                date(a.get("report_period_start")),date(a.get("report_period_end")),
                decimal(a.get("predicted_count")),decimal(a.get("top_percent")));
        var reasons=result.reasons().stream().map(r->new Reason(integer(r.get("rank")),(String)r.get("reason_type"),
                (String)r.get("zone_type"),decimal(r.get("distance_m")),decimal(r.get("threshold_m")),
                decimal(r.get("accuracy_margin_m")),(String)r.get("description"))).toList();
        // Fixed API contract: never consult current user preferences, model or server clock on replay.
        var expiry=kst(ParkingStore.instant(result.event().get("detected_at")).plus(ParkingNotificationPolicy.VALIDITY));
        return new ParkingResponse(result.parkingEventId(),(String)result.event().get("address"),new Assessment(
                (String)a.get("status"),integer(a.get("level")),(String)a.get("prediction_status"),(String)a.get("primary_zone_type"),
                reasons,stats,integer(a.get("reference_fine_amount")),integer(a.get("attempt_count")),
                new Notification((String)a.get("notify_decision"),expiry,(String)a.get("message_title"),(String)a.get("message_body")),
                a.get("evaluated_at")==null ? null : kst(ParkingStore.instant(a.get("evaluated_at")))));
    }
    private static Integer integer(Object value) { return value==null ? null : ((Number)value).intValue(); }
    private static Double decimal(Object value) { return value==null ? null : ((Number)value).doubleValue(); }
    private static LocalDate date(Object value) { return value==null ? null : ((java.sql.Date)value).toLocalDate(); }
    private static OffsetDateTime kst(Instant instant) { return instant.atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime(); }
}
