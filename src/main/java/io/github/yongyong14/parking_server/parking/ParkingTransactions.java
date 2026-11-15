package io.github.yongyong14.parking_server.parking;

import java.time.Clock;
import java.util.*;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.github.yongyong14.parking_server.global.exception.*;
import io.github.yongyong14.parking_server.global.security.AgreementPolicy;

/** Transactional entry point. HTTP mapping and exception translation run outside this proxy. */
@Service
@Lazy
public class ParkingTransactions {
    private final ParkingStore store;
    private final ParkingAssessmentEngine engine;
    private final ParkingSettings settings;
    private final AgreementPolicy agreements;
    private final Clock clock;
    private final org.springframework.context.ApplicationEventPublisher events;
    @org.springframework.beans.factory.annotation.Autowired
    public ParkingTransactions(ParkingStore store, ParkingAssessmentEngine engine, ParkingSettings settings,
            AgreementPolicy agreements, Clock clock, org.springframework.context.ApplicationEventPublisher events) {
        this.store=store; this.engine=engine; this.settings=settings; this.agreements=agreements; this.clock=clock;
        this.events=events;
    }
    public ParkingTransactions(ParkingStore store, ParkingAssessmentEngine engine, ParkingSettings settings,
            AgreementPolicy agreements, Clock clock) {
        this(store,engine,settings,agreements,clock,event->{});
    }
    public record Created(UUID userId, UUID eventId) {}
    public record Result(UUID parkingEventId, boolean created, Map<String,Object> assessment,
            List<Map<String,Object>> reasons, Map<String,Object> event) {}
    @Transactional
    public Result save(UUID user, ParkingRequest request) {
        request=request.validated(settings);
        String hash=request.requestHash();
        store.lockUserAndConsent(user,agreements);
        UUID event=store.existing(user,request.clientEventId());
        boolean created=false;
        if (event==null) {
            if (request.detectedAt().toInstant().isAfter(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS).plus(settings.futureTolerance())))
                throw new BusinessException(ErrorCode.INVALID_TIME);
            store.validateDistance(request);
            String name=null;
            try { name=store.activeVehicle(user,request.vehicleId()); }
            catch (BusinessException missing) {
                if (missing.getErrorCode()!=ErrorCode.VEHICLE_NOT_FOUND) throw missing;
                // This is application validation, not a failed SQL transaction. A concurrent winner may now exist.
                event=store.existing(user,request.clientEventId());
                if (event==null) throw missing;
            }
            if (name!=null) {
                event=store.insert(user,request,hash,name);
                created=event!=null;
            }
        }
        int attempt=1;
        if (!created) {
            var existing=store.locked(user,request.clientEventId());
            event=(UUID)existing.get("id");
            if (!hash.equals(existing.get("request_hash"))) throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            var assessment=store.assessment(event);
            if (!"FAILED".equals(assessment.get("status")) || ((Number)assessment.get("attempt_count")).intValue()>=2)
                return new Result(event,false,assessment,store.reasons((UUID)assessment.get("id")),store.event(user,event));
            attempt=2;
        }
        engine.evaluate(user,event,request,attempt,clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        store.logUse(user,event);
        var assessment=store.assessment(event);
        if (created) events.publishEvent(new Created(user,event));
        return new Result(event,created,assessment,store.reasons((UUID)assessment.get("id")),store.event(user,event));
    }
}
