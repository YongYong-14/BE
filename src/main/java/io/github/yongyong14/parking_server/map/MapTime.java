package io.github.yongyong14.parking_server.map;

import java.time.*;
import io.github.yongyong14.parking_server.global.exception.*;

final class MapTime {
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static MapRiskResponse.Slot slot(Instant instant, String policy, boolean holiday) {
        var local = instant.atZone(SEOUL);
        if (!policy.equals("AS_SUNDAY") && !policy.equals("NONE"))
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        return new MapRiskResponse.Slot(holiday && policy.equals("AS_SUNDAY") ? 7
                : local.getDayOfWeek().getValue(), local.getHour() / 3);
    }
}
