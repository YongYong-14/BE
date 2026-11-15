package io.github.yongyong14.parking_server.parking;

import java.time.*;
import java.util.*;

public final class ParkingQueryResponse {
    private ParkingQueryResponse() {}
    public record Point(double lat, double lng) {}
    public record Event(UUID id, OffsetDateTime detectedAt, Point confirmedLocation, String address, String vehicleName) {}
    public record Period(LocalDate start, LocalDate end) {}
    public record Summary(String status, Integer level, String predictionStatus) {}
    public record Latest(Event event, Summary assessment, Period dataPeriod) {}
    public record Peak(int weekday, int timeSlot, List<Long> slotCounts) {}
    public record DetailStats(Integer reportCount, Integer reportRadiusM, LocalDate periodStart, LocalDate periodEnd,
            Double predictedCount, Double topPercent, Peak peak) {}
    public record DetailAssessment(String status, Integer level, String predictionStatus, String primaryZoneType,
            List<ParkingResponse.Reason> reasons, DetailStats stats, Integer referenceFineAmount, int attemptCount,
            @com.fasterxml.jackson.annotation.JsonProperty("notify") ParkingResponse.Notification notification, OffsetDateTime evaluatedAt) {
        static DetailAssessment from(ParkingResponse.Assessment a, Peak peak) {
            var s=a.stats();
            return new DetailAssessment(a.status(),a.level(),a.predictionStatus(),a.primaryZoneType(),a.reasons(),
                    new DetailStats(s.reportCount(),s.reportRadiusM(),s.periodStart(),s.periodEnd(),s.predictedCount(),s.topPercent(),peak),
                    a.referenceFineAmount(),a.attemptCount(),a.notification(),a.evaluatedAt());
        }
    }
    public record Detail(Event event, DetailAssessment assessment) {}
    public record ListEvent(UUID id, OffsetDateTime detectedAt, String address) {}
    public record ListAssessment(String status, Integer level, String predictionStatus, String primaryZoneType,
            Double distanceM, Integer referenceFineAmount, Double topPercent) {}
    public record Item(ListEvent event, ListAssessment assessment) {}
    public record Items(List<Item> items) {}
    static OffsetDateTime time(Object value) {
        return ParkingStore.instant(value).atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime();
    }
    static Integer integer(Object value) { return value==null ? null : ((Number)value).intValue(); }
    static Double decimal(Object value) { return value==null ? null : ((Number)value).doubleValue(); }
    static Event event(Map<String,Object> row) {
        return new Event((UUID)row.get("id"),time(row.get("detected_at")),
                new Point(decimal(row.get("lat")),decimal(row.get("lng"))),
                (String)row.get("address"),(String)row.get("vehicle_name_snapshot"));
    }
}
