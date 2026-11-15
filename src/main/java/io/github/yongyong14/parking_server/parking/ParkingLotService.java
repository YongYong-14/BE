package io.github.yongyong14.parking_server.parking;

import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import io.github.yongyong14.parking_server.global.exception.*;
import io.github.yongyong14.parking_server.global.security.AgreementPolicy;

@Service
@Lazy
public class ParkingLotService {
    private final ParkingStore store;
    private final AgreementPolicy agreements;
    private final Clock clock;
    public ParkingLotService(ParkingStore store, AgreementPolicy agreements, Clock clock) {
        this.store=store; this.agreements=agreements; this.clock=clock;
    }
    public static void validate(double lat,double lng,int radius) {
        if (!Double.isFinite(lat) || !Double.isFinite(lng) || lat < -90 || lat > 90 || lng < -180 || lng > 180
                || radius <= 0 || radius > 1000) throw new BusinessException(ErrorCode.INVALID_INPUT);
    }
    // Acquire the user lock first, then read consent with a fresh READ COMMITTED statement snapshot.
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public ParkingLotResponse nearby(UUID user,double lat,double lng,int radius) {
        validate(lat,lng,radius);
        store.lockUserAndConsent(user,agreements);
        LocalDate today=clock.instant().atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        var result=find(lat,lng,radius,today);
        store.logUse(user,null,"주변 주차장 조회","요청 좌표");
        return result;
    }
    // One statement snapshot covers the active import, holidays, initial radius and expansion.
    // MATERIALIZED candidates are searched once, using the geography GiST index.
    static final String NEARBY_SQL="""
                WITH search_point AS (
                    SELECT ST_SetSRID(ST_MakePoint(:lng,:lat),4326)::geography AS location,
                           CASE WHEN extract(isodow FROM CAST(:day AS date))=7 OR
                                     EXISTS(SELECT 1 FROM public.holidays WHERE holiday_date=CAST(:day AS date)) THEN 'holiday'
                                WHEN extract(isodow FROM CAST(:day AS date))=6 THEN 'saturday'
                                ELSE 'weekday' END AS day_type
                ), candidates AS MATERIALIZED (
                SELECT p.id,p.name,p.address,ST_Distance(p.location,q.location) AS distance_m,
                       ST_Y(p.location::geometry) AS lat,ST_X(p.location::geometry) AS lng,
                       p.parking_type,p.fee_type,p.fee_info,p.capacity,
                       ST_DWithin(p.location,q.location,:radius) AS within_requested,
                       CASE q.day_type WHEN 'holiday' THEN p.holiday_open
                                       WHEN 'saturday' THEN p.saturday_open ELSE p.weekday_open END AS opens,
                       CASE q.day_type WHEN 'holiday' THEN p.holiday_close
                                       WHEN 'saturday' THEN p.saturday_close ELSE p.weekday_close END AS closes
                FROM public.parking_lots p
                JOIN public.data_imports i ON i.id=p.import_id AND i.active AND i.source_dataset='yongin_parking_lots'
                CROSS JOIN search_point q
                WHERE ST_DWithin(p.location,q.location,CASE WHEN :radius=500 THEN 1000 ELSE :radius END)
                ), decision AS (
                    SELECT (:radius=500 AND NOT EXISTS(SELECT 1 FROM candidates WHERE within_requested)) AS expanded
                )
                SELECT CASE WHEN d.expanded THEN 1000 ELSE :radius END AS radius_m,d.expanded,c.*,
                       to_char(c.opens,'HH24:MI') || '-' || to_char(c.closes,'HH24:MI') AS hours
                FROM decision d LEFT JOIN candidates c ON (d.expanded OR c.within_requested)
                ORDER BY c.distance_m,c.id
                """;
    ParkingLotResponse find(double lat,double lng,int radius,LocalDate today) {
        return store.jdbc().query(NEARBY_SQL,Map.of("lat",lat,"lng",lng,"radius",radius,"day",today),rs->{
            var items=new ArrayList<ParkingLotResponse.Item>();
            // The LEFT JOIN retains radius/expansion metadata even when no parking lots exist.
            rs.next();
            int actualRadius=rs.getInt("radius_m");
            boolean expanded=rs.getBoolean("expanded");
            do {
                if (rs.getObject("id")==null) continue;
                items.add(new ParkingLotResponse.Item(rs.getObject("id",UUID.class),rs.getString("name"),rs.getString("address"),
                        rs.getDouble("distance_m"),new ParkingLotResponse.Location(rs.getDouble("lat"),rs.getDouble("lng")),
                        rs.getString("parking_type"),rs.getString("fee_type"),rs.getString("fee_info"),rs.getString("hours"),
                        rs.getObject("capacity",Integer.class)));
            } while(rs.next());
            return new ParkingLotResponse(actualRadius,expanded,items);
        });
    }
}
