package io.github.yongyong14.parking_server.map;

import java.time.LocalDate;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

public record MapRiskResponse(List<FacilityZone> facilityZones, List<Cell> cells, Slot slot,
        @Schema(types = {"object", "null"}) DataPeriod dataPeriod) {
    public record Location(double lat, double lng) {}
    public record FacilityZone(String zoneType, Location location, double radiusM) {}
    public record Cell(Location location, int sizeM, double topPercent, long reportCount) {}
    public record Slot(int weekday, int timeSlot) {}
    public record DataPeriod(LocalDate start, LocalDate end) {}
}
