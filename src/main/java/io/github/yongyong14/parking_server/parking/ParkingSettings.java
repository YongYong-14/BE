package io.github.yongyong14.parking_server.parking;

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Rule selection and ranking are deployment contracts, never inferred from the newest row. */
@Component
public record ParkingSettings(
        @Value("${app.parking.rule-set:}") String ruleSet,
        @Value("${app.parking.facility-priority:}") List<String> facilityPriority,
        @Value("${app.parking.future-tolerance:2m}") Duration futureTolerance,
        @Value("${app.parking.last-known-max-age:10m}") Duration lastKnownMaxAge,
        @Value("${app.parking.max-remeasure-count:10}") int maxRemeasureCount,
        @Value("${app.parking.report-radius-m:50}") int reportRadiusM) {
    public ParkingSettings {
        facilityPriority = List.copyOf(facilityPriority);
        if (futureTolerance.isNegative() || lastKnownMaxAge.isNegative() || maxRemeasureCount < 0
                || maxRemeasureCount > 10 || reportRadiusM <= 0) throw new IllegalArgumentException("Invalid parking settings");
    }
}
