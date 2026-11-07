package io.github.yongyong14.parking_server.parking;

import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ParkingNotificationPolicy {
    public static final Duration VALIDITY = Duration.ofMinutes(30);
    private final double immediateTopPercent;
    public ParkingNotificationPolicy(@Value("${app.parking.immediate-top-percent:2}") double immediateTopPercent) {
        if (!Double.isFinite(immediateTopPercent) || immediateTopPercent < 0 || immediateTopPercent > 100)
            throw new IllegalArgumentException("Invalid immediate notification threshold");
        this.immediateTopPercent = immediateTopPercent;
    }
    public void validateBoundary(double cautionTopPercent) {
        if (immediateTopPercent > cautionTopPercent)
            throw new IllegalStateException("Immediate threshold exceeds selected prediction version caution threshold");
    }
    public String decide(String status, Integer level, Double topPercent, Instant detected, Instant evaluated,
            boolean cautionEnabled, boolean nightEnabled) {
        if (evaluated.isAfter(detected.plus(VALIDITY)) || !"SUCCEEDED".equals(status)) return "NONE";
        if (Integer.valueOf(1).equals(level)) return "SEND";
        if (!Integer.valueOf(2).equals(level)) return "NONE";
        if (topPercent != null && topPercent <= immediateTopPercent) return "SEND";
        if (!cautionEnabled) return "SUPPRESSED_SETTING";
        int hour = detected.atZone(ZoneId.of("Asia/Seoul")).getHour();
        if ((hour >= 22 || hour < 7) && !nightEnabled) return "SUPPRESSED_NIGHT";
        return "SEND";
    }
    boolean needsSettings(String status, Integer level, Double top, Instant detected, Instant evaluated) {
        return "SUCCEEDED".equals(status) && Integer.valueOf(2).equals(level)
                && !evaluated.isAfter(detected.plus(VALIDITY)) && top != null && top > immediateTopPercent;
    }
}
