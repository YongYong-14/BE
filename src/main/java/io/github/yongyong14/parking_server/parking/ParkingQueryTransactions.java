package io.github.yongyong14.parking_server.parking;

import java.util.*;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import io.github.yongyong14.parking_server.global.exception.*;
import static io.github.yongyong14.parking_server.parking.ParkingQueryResponse.*;

/** Read snapshots never evaluate risk or consult current notification preferences. */
@Service
@Lazy
@Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
public class ParkingQueryTransactions {
    private final ParkingStore store;
    public ParkingQueryTransactions(ParkingStore store) { this.store=store; }
    private static final String EVENT="""
            SELECT id,detected_at,address,vehicle_name_snapshot,
              ST_Y(confirmed_location::geometry) AS lat,ST_X(confirmed_location::geometry) AS lng
            FROM public.parking_events WHERE user_id=:u
            """;
    public Latest latest(UUID user) {
        var periods=store.jdbc().queryForList("SELECT training_start,observed_end FROM public.prediction_versions WHERE active",Map.of());
        if (periods.size()>1) throw new IllegalStateException("Multiple active versions");
        var period=periods.isEmpty() ? null : new Period(((java.sql.Date)periods.getFirst().get("training_start")).toLocalDate(),
                ((java.sql.Date)periods.getFirst().get("observed_end")).toLocalDate());
        var rows=store.jdbc().queryForList(EVENT+" ORDER BY detected_at DESC,id DESC LIMIT 1",Map.of("u",user));
        if (rows.isEmpty()) return new Latest(null,null,period);
        var e=rows.getFirst(); var a=store.assessment((UUID)e.get("id"));
        return new Latest(event(e),new Summary((String)a.get("status"),integer(a.get("level")),(String)a.get("prediction_status")),period);
    }
    Map<String,Object> owned(UUID user,UUID id) {
        var rows=store.jdbc().queryForList(EVENT+" AND id=:id",Map.of("u",user,"id",id));
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.PARKING_EVENT_NOT_FOUND);
        return rows.getFirst();
    }
    public Detail detail(UUID user,UUID id) {
        var e=owned(user,id); var a=store.assessment(id);
        var saved=ParkingResponse.from(new ParkingTransactions.Result(id,false,a,store.reasons((UUID)a.get("id")),e));
        return new Detail(event(e),DetailAssessment.from(saved.assessment(),peak(e,a)));
    }
    private Peak peak(Map<String,Object> event,Map<String,Object> assessment) {
        Object version=assessment.get("prediction_version_id"), source=assessment.get("source_import_id");
        if (version==null || source==null) return null;
        var versions=store.jdbc().queryForList("SELECT source_import_id,training_start,observed_end,holiday_policy,projected_srid,cell_size_m FROM public.prediction_versions WHERE id=:v",Map.of("v",version));
        if (versions.isEmpty()) return null;
        var v=versions.getFirst();
        if (!Objects.equals(source,v.get("source_import_id")) || v.get("training_start")==null || v.get("observed_end")==null
                || !Set.of("AS_SUNDAY","NONE").contains(v.get("holiday_policy"))
                || integer(v.get("projected_srid"))!=5186 || integer(v.get("cell_size_m"))!=50) return null;
        var grid=new PostgisGridResolver().resolve(store.jdbc(),java.math.BigDecimal.valueOf(decimal(event.get("lat"))),
                java.math.BigDecimal.valueOf(decimal(event.get("lng"))),5186,50);
        if (grid.id()==null) return null;
        var detected=time(event.get("detected_at"));
        boolean holiday=assessment.get("is_holiday") instanceof Boolean value ? value : Boolean.TRUE.equals(store.jdbc().queryForObject(
                "SELECT EXISTS(SELECT 1 FROM public.holidays WHERE holiday_date=:day)",Map.of("day",detected.toLocalDate()),Boolean.class));
        int weekday=holiday && "AS_SUNDAY".equals(v.get("holiday_policy")) ? 7 : detected.getDayOfWeek().getValue();
        var rows=store.jdbc().queryForList("""
                SELECT time_slot,report_count FROM public.report_aggregates
                WHERE import_id=:source AND grid_id=:grid AND weekday=:weekday
                  AND period_start=:start AND period_end=:end ORDER BY time_slot
                """,Map.of("source",source,"grid",grid.id(),"weekday",weekday,"start",v.get("training_start"),"end",v.get("observed_end")));
        if (rows.isEmpty()) return null;
        var counts=new ArrayList<>(Collections.nCopies(8,0L));
        for (var row:rows) counts.set(integer(row.get("time_slot")),((Number)row.get("report_count")).longValue());
        int slot=0;
        for (int i=1;i<8;i++) if (counts.get(i)>counts.get(slot)) slot=i;
        return new Peak(weekday,slot,List.copyOf(counts));
    }
    public Items list(UUID user,String level,int limit) {
        var rows=store.jdbc().queryForList("""
                SELECT e.id,e.detected_at,e.address,a.status,a.level,a.prediction_status,a.primary_zone_type,
                  CASE WHEN a.level=1 THEN r.distance_m END AS distance_m,a.reference_fine_amount,
                  CASE WHEN a.level=2 THEN a.top_percent END AS top_percent
                FROM public.parking_events e JOIN public.risk_assessments a ON a.parking_event_id=e.id
                LEFT JOIN public.assessment_reasons r ON r.assessment_id=a.id AND r.rank=1
                WHERE e.user_id=:u
                """+("ALL".equals(level) ? "" : " AND a.level=:level")+" ORDER BY e.detected_at DESC,e.id DESC LIMIT :limit",
                Map.of("u",user,"level","ALL".equals(level) ? 0 : Integer.parseInt(level),"limit",limit));
        return new Items(rows.stream().map(r->new Item(new ListEvent((UUID)r.get("id"),time(r.get("detected_at")),(String)r.get("address")),
                new ListAssessment((String)r.get("status"),integer(r.get("level")),(String)r.get("prediction_status"),
                        (String)r.get("primary_zone_type"),decimal(r.get("distance_m")),integer(r.get("reference_fine_amount")),decimal(r.get("top_percent"))))).toList());
    }
}
