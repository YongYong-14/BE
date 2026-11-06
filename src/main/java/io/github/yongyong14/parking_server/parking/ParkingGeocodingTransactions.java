package io.github.yongyong14.parking_server.parking;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.github.yongyong14.parking_server.global.security.AgreementPolicy;

@Service
@Lazy
public class ParkingGeocodingTransactions {
    private final ParkingStore store;
    private final AgreementPolicy agreements;
    private final ReverseGeocoder geocoder;
    public ParkingGeocodingTransactions(ParkingStore store,AgreementPolicy agreements,ReverseGeocoder geocoder) {
        this.store=store; this.agreements=agreements; this.geocoder=geocoder;
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void fillAddress(ParkingTransactions.Created event) {
        if (!geocoder.enabled()) return;
        // Same order as save/withdrawal. Keep locks across the bounded call so withdrawal cannot overtake disclosure.
        store.lockUserAndConsent(event.userId(),agreements);
        var p=Map.of("u",event.userId(),"id",event.eventId());
        var rows=store.jdbc.queryForList("""
                SELECT ST_Y(confirmed_location::geometry) AS lat,ST_X(confirmed_location::geometry) AS lng
                FROM public.parking_events WHERE id=:id AND user_id=:u AND address IS NULL FOR UPDATE
                """,p);
        if (rows.isEmpty()) return;
        store.jdbc.update("""
                INSERT INTO public.location_access_logs(user_id,parking_event_id,action,purpose,recipient)
                VALUES (:u,:id,'PROVIDE','주차 주소 변환 요청','Kakao Local')
                """,p);
        java.util.Optional<String> address;
        try {
            address=geocoder.address(new BigDecimal(rows.getFirst().get("lat").toString()),new BigDecimal(rows.getFirst().get("lng").toString()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return;
        } catch (Exception unavailable) {
            // Preserve the transfer-attempt log. The original parking transaction is already committed.
            return;
        }
        address.filter(a->!a.isBlank()).ifPresent(a->store.jdbc.update(
                "UPDATE public.parking_events SET address=:address WHERE id=:id AND user_id=:u AND address IS NULL",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(p).addValue("address",a)));
    }
}
