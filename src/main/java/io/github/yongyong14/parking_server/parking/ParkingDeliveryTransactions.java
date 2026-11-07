package io.github.yongyong14.parking_server.parking;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.github.yongyong14.parking_server.global.exception.*;

@Service
@Lazy
public class ParkingDeliveryTransactions {
    private final ParkingStore store;
    private final Clock clock;
    private final ParkingSettings settings;
    public ParkingDeliveryTransactions(ParkingStore store,Clock clock,ParkingSettings settings) {
        this.store=store; this.clock=clock; this.settings=settings;
    }
    @Transactional
    public void report(UUID user,UUID id,ParkingDeliveryRequest request) {
        request=request.validated();
        // Same lock order as POST replay: event first, then assessment. READ COMMITTED reads the winner after waiting.
        var key=Map.of("u",user,"id",id);
        var events=store.jdbc().queryForList("SELECT confirmed_at FROM public.parking_events WHERE user_id=:u AND id=:id FOR UPDATE",key);
        if (events.isEmpty()) throw new BusinessException(ErrorCode.PARKING_EVENT_NOT_FOUND);
        if (request.deliveredAt()!=null) {
            var instant=request.deliveredAt().toInstant();
            if (instant.isBefore(ParkingStore.instant(events.getFirst().get("confirmed_at")))
                    || instant.isAfter(clock.instant().truncatedTo(ChronoUnit.MICROS).plus(settings.futureTolerance())))
                throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        var a=store.jdbc().queryForMap("SELECT id,notify_decision,delivered_at,delivery_failure FROM public.risk_assessments WHERE parking_event_id=:id FOR UPDATE",key);
        if (!"SEND".equals(a.get("notify_decision"))) throw new BusinessException(ErrorCode.DELIVERY_NOT_EXPECTED);
        if (a.get("delivered_at")!=null) return;
        if (request.deliveredAt()!=null) {
            store.jdbc().update("UPDATE public.risk_assessments SET delivered_at=:at,delivery_failure=NULL WHERE id=:a",
                    Map.of("a",a.get("id"),"at",Timestamp.from(request.deliveredAt().toInstant())));
        } else if (a.get("delivery_failure")==null) {
            store.jdbc().update("UPDATE public.risk_assessments SET delivery_failure=:failure WHERE id=:a",
                    Map.of("a",a.get("id"),"failure",request.failure()));
        }
    }
}
