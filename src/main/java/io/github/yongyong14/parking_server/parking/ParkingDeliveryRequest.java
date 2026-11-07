package io.github.yongyong14.parking_server.parking;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import io.github.yongyong14.parking_server.global.exception.*;

public record ParkingDeliveryRequest(OffsetDateTime deliveredAt, String failure) {
    public ParkingDeliveryRequest validated() {
        if ((deliveredAt==null)==(failure==null)
                || (failure!=null && !Set.of("NO_PERMISSION","DISPLAY_FAILED","UNKNOWN").contains(failure)))
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        return deliveredAt==null ? this : new ParkingDeliveryRequest(
                deliveredAt.toInstant().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC),null);
    }
}
