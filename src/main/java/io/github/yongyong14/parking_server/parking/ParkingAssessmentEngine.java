package io.github.yongyong14.parking_server.parking;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Component;

/** Evaluation, reasons and the immutable notification decision share the event transaction. */
@Component
@Lazy
public class ParkingAssessmentEngine {
    private final ParkingStore store;
    private final ParkingSettings settings;
    private final GridResolver grids;
    private final ParkingNotificationPolicy notifications;
    @org.springframework.beans.factory.annotation.Autowired
    public ParkingAssessmentEngine(ParkingStore store, ParkingSettings settings, GridResolver grids, ParkingNotificationPolicy notifications) {
        this.store=store; this.settings=settings; this.grids=grids; this.notifications=notifications;
    }
    public ParkingAssessmentEngine(ParkingStore store, ParkingSettings settings, GridResolver grids) {
        this(store,settings,grids,new ParkingNotificationPolicy(2));
    }
    record Snapshot(String ruleSet, Map<String,Object> version, UUID statisticsImport, List<UUID> facilityImports) {}
    Snapshot snapshot() {
        // One statement / MVCC snapshot for all mutable active selectors.
        var rows=store.jdbc.queryForList("""
                SELECT rs.rule_set AS selected_rule,pv.*,
                  (SELECT id FROM public.data_imports WHERE source_dataset='yongin_enforcement' AND active) AS statistics_import,
                  ARRAY(SELECT id FROM public.data_imports WHERE active ORDER BY id) AS facility_imports
                FROM public.zone_rule_sets rs LEFT JOIN public.prediction_versions pv ON pv.active
                WHERE rs.rule_set=:rule AND rs.published_at IS NOT NULL
                """,Map.of("rule",settings.ruleSet()));
        if (rows.size()!=1) throw new ParkingConfigurationException(ParkingConfigurationException.Code.PUBLIC_RULE_SET_UNAVAILABLE);
        var row=rows.getFirst();
        Map<String,Object> version=row.get("id")==null ? null : row;
        if (version!=null && version.get("frozen_at")==null) throw new ParkingConfigurationException(ParkingConfigurationException.Code.MODEL_VERSION_NOT_FROZEN);
        List<UUID> imports;
        try { imports=Arrays.asList((UUID[])((java.sql.Array)row.get("facility_imports")).getArray()); }
        catch (java.sql.SQLException e) { throw new IllegalStateException("Cannot read data snapshot",e); }
        return new Snapshot((String)row.get("selected_rule"),version,(UUID)row.get("statistics_import"),imports);
    }
    public void evaluate(UUID user, UUID event, ParkingRequest request, int attempt, Instant now) {
        Snapshot snapshot=snapshot();
        var previous=store.jdbc.queryForList("SELECT id FROM public.risk_assessments WHERE parking_event_id=:event",Map.of("event",event));
        UUID assessment=previous.isEmpty() ? UUID.randomUUID() : (UUID)previous.getFirst().get("id");
        ZonedDateTime kst=request.detectedAt().atZoneSameInstant(ZoneId.of("Asia/Seoul"));
        boolean holiday=Boolean.TRUE.equals(store.jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM public.holidays WHERE holiday_date=:day)",Map.of("day",kst.toLocalDate()),Boolean.class));
        int actual=kst.getDayOfWeek().getValue();
        Map<String,Object> v=snapshot.version;
        if (v!=null) notifications.validateBoundary(((Number)v.get("caution_top_percent")).doubleValue());
        int weekday=v!=null && holiday && "AS_SUNDAY".equals(v.get("holiday_policy")) ? 7 : actual;
        var values=new MapSqlParameterSource().addValue("id",assessment).addValue("event",event)
                .addValue("attempt",attempt).addValue("reference",Timestamp.from(request.detectedAt().toInstant()))
                .addValue("actual",actual).addValue("weekday",weekday).addValue("slot",kst.getHour()/3)
                .addValue("holiday",holiday).addValue("rule",snapshot.ruleSet)
                .addValue("version",v==null ? null : v.get("id")).addValue("source",snapshot.statisticsImport)
                .addValue("grid",null).addValue("status","FAILED").addValue("level",null)
                .addValue("prediction",null).addValue("primary",null).addValue("fine",null)
                .addValue("predicted",null).addValue("top",null).addValue("error",null)
                .addValue("reports",null).addValue("groups",null).addValue("radius",null)
                .addValue("start",null).addValue("end",null).addValue("now",Timestamp.from(now));
        List<Map<String,Object>> reasons=new ArrayList<>();
        var p=store.requestParams(request).addValue("rule",snapshot.ruleSet)
                .addValue("imports",snapshot.facilityImports);
        // Only actual MVP facilities. Published rules must express exactly the agreed legal thresholds.
        var rules=store.jdbc.queryForList("""
                SELECT zone_type,geometry_rule,legal_distance_m FROM public.zone_rules
                WHERE rule_set=:rule AND zone_type IN ('HYDRANT','BUS_STOP')
                """,p);
        if (rules.size()!=2 || rules.stream().anyMatch(r -> !"DISTANCE".equals(r.get("geometry_rule"))
                || ((Number)r.get("legal_distance_m")).doubleValue() != ("HYDRANT".equals(r.get("zone_type")) ? 5 : 10)))
            throw new ParkingConfigurationException(ParkingConfigurationException.Code.FACILITY_RULE_CONTRACT_MISMATCH);
        var hits=snapshot.facilityImports.isEmpty() ? new ArrayList<Map<String,Object>>() : store.jdbc.queryForList("""
                SELECT f.id AS facility_id,f.zone_type,r.legal_distance_m AS threshold_m,r.fine_amount_car,r.message_template_title,
                  ST_Distance(f.spatial_data,ST_SetSRID(ST_MakePoint(CAST(:lng AS float8),CAST(:lat AS float8)),4326)::geography) AS distance_m,
                  z.label
                FROM public.facilities f JOIN public.zone_rules r ON r.zone_type=f.zone_type AND r.rule_set=:rule
                JOIN public.zone_types z ON z.zone_type=f.zone_type
                WHERE f.import_id IN (:imports) AND f.zone_type IN ('HYDRANT','BUS_STOP')
                  AND ST_DWithin(f.spatial_data,ST_SetSRID(ST_MakePoint(CAST(:lng AS float8),CAST(:lat AS float8)),4326)::geography,r.legal_distance_m)
                """,p);
        if (!hits.isEmpty()) {
            Set<String> types=new HashSet<>();
            hits.forEach(h->types.add((String)h.get("zone_type")));
            if (types.size()>1 && !settings.facilityPriority().containsAll(types)) {
                values.addValue("error","FACILITY_PRIORITY_UNCONFIGURED");
            } else {
                hits.sort(Comparator.<Map<String,Object>>comparingInt(h->settings.facilityPriority().indexOf(h.get("zone_type")))
                        .thenComparingDouble(h->((Number)h.get("distance_m")).doubleValue())
                        .thenComparing(h->h.get("facility_id").toString()));
                var first=hits.getFirst();
                values.addValue("status","SUCCEEDED").addValue("level",1).addValue("prediction","SKIPPED")
                        .addValue("primary",first.get("zone_type")).addValue("fine",first.get("fine_amount_car"));
                for (var hit:hits) reasons.add(new HashMap<>(hit));
            }
        } else if (v==null) {
            values.addValue("status","PARTIAL").addValue("prediction","NO_ACTIVE_VERSION");
        } else if (!Objects.equals(v.get("source_import_id"),snapshot.statisticsImport)) {
            values.addValue("error","SOURCE_IMPORT_MISMATCH");
        } else if (!Set.of("AS_SUNDAY","NONE").contains(v.get("holiday_policy"))
                || !"GE_SHARE".equals(v.get("tie_method"))
                || !Set.of("SAME_SLOT","GLOBAL").contains(v.get("percentile_basis"))) {
            values.addValue("error","UNSUPPORTED_PREDICTION_CONTRACT");
        } else {
            try {
                var grid=grids.resolve(store.jdbc,request.confirmedLocation().lat(),request.confirmedLocation().lng(),
                        ((Number)v.get("projected_srid")).intValue(),((Number)v.get("cell_size_m")).intValue());
                values.addValue("grid",grid.id());
                if (grid.id()==null || !grid.inModelArea()) {
                    values.addValue("status","PARTIAL").addValue("prediction","OUT_OF_AREA");
                } else {
                    var key=Map.of("version",v.get("id"),"grid",grid.id(),"weekday",weekday,"slot",kst.getHour()/3);
                    boolean target=Boolean.TRUE.equals(store.jdbc.queryForObject("""
                            SELECT EXISTS(SELECT 1 FROM public.prediction_target_grids WHERE version_id=:version AND grid_id=:grid)
                            """,key,Boolean.class));
                    var prediction=predictions(key);
                    if (!target) values.addValue("status","PARTIAL").addValue("prediction","NOT_TARGET");
                    else if (prediction.isEmpty()) values.addValue("prediction","MISSING_ROW").addValue("error","MISSING_ROW");
                    else {
                        double top=((Number)prediction.getFirst().get("top_percent")).doubleValue();
                        double predicted=((Number)prediction.getFirst().get("smoothed_count")).doubleValue();
                        double threshold=((Number)v.get("caution_top_percent")).doubleValue();
                        if (!Double.isFinite(top) || top<0 || top>100 || !Double.isFinite(predicted) || predicted<0
                                || !Double.isFinite(threshold) || threshold<0 || threshold>100) {
                            values.addValue("error","INVALID_PREDICTION_VALUE");
                        } else {
                            int level=top<=threshold ? 2 : 3;
                            values.addValue("status","SUCCEEDED").addValue("level",level).addValue("prediction","OK")
                                    .addValue("predicted",predicted).addValue("top",top);
                            if (level==2) reasons.add(new HashMap<>(Map.of("model",true,
                                    "description",modelDescription(weekday,kst.getHour()/3,top)+" (격자 근사값)")));
                        }
                    }
                }
            } catch (ParkingDataConfiguration.DataContractUnavailable e) { values.addValue("error",e.getMessage()); }
        }
        if (snapshot.statisticsImport!=null) {
            p.addValue("source",snapshot.statisticsImport).addValue("radius",settings.reportRadiusM());
            var stats=store.jdbc.queryForMap("""
                    SELECT COALESCE(SUM(report_count),0)::int AS reports,COALESCE(SUM(event_group_count),0)::int AS groups,
                      MIN(period_start) AS start,MAX(period_end) AS end FROM public.parcel_report_stats
                    WHERE import_id=:source AND ST_DWithin(location,ST_SetSRID(ST_MakePoint(CAST(:lng AS float8),CAST(:lat AS float8)),4326)::geography,:radius)
                    """,p);
            values.addValue("reports",stats.get("reports")).addValue("groups",stats.get("groups"))
                    .addValue("start",stats.get("start")).addValue("end",stats.get("end")).addValue("radius",settings.reportRadiusM());
        }
        String status=(String)values.getValue("status");
        Integer level=(Integer)values.getValue("level");
        Double top=(Double)values.getValue("top");
        boolean caution=false,night=false;
        if (notifications.needsSettings(status,level,top,request.detectedAt().toInstant(),now)) {
            var preferences=store.notificationSettings(user);
            caution=(Boolean)preferences.get("caution_enabled"); night=(Boolean)preferences.get("night_enabled");
        }
        String title=null,body=null;
        if ("SUCCEEDED".equals(status) && Integer.valueOf(1).equals(level)) {
            var primary=hits.getFirst();
            title=(String)primary.get("message_template_title");
            if (title==null || title.isBlank()) title="여기는 "+primary.get("label")+" 주변이에요";
            body=values.getValue("reports")==null ? "확정 위치가 시설의 법정 거리 이내예요"
                    : "이 근처에서 신고 "+values.getValue("reports")+"건";
            if (values.getValue("start")!=null && values.getValue("end")!=null)
                body+=" ("+values.getValue("start")+" ~ "+values.getValue("end")+")";
        } else if ("SUCCEEDED".equals(status) && Integer.valueOf(2).equals(level)) {
            title="여기는 신고가 잦은 곳이에요";
            body=modelDescription(weekday,kst.getHour()/3,top)+" (격자 근사값)";
        }
        values.addValue("notify",notifications.decide(status,level,top,request.detectedAt().toInstant(),now,caution,night))
                .addValue("title",title).addValue("body",body);
        // Replacing a FAILED assessment and its reasons is atomic with attempt increment and logs.
        store.jdbc.update("DELETE FROM public.assessment_reasons WHERE assessment_id=:id",Map.of("id",assessment));
        store.jdbc.update("""
                INSERT INTO public.risk_assessments(id,parking_event_id,status,level,prediction_status,primary_zone_type,
                  attempt_count,reference_time,weekday_actual,weekday,time_slot,is_holiday,grid_id,zone_rule_set,
                  prediction_version_id,source_import_id,report_count,event_group_count,report_count_radius_m,
                  report_period_start,report_period_end,predicted_count,top_percent,reference_fine_amount,error_code,evaluated_at,
                  notify_decision,message_title,message_body)
                VALUES (:id,:event,:status,:level,:prediction,:primary,:attempt,:reference,:actual,:weekday,:slot,:holiday,
                  :grid,:rule,:version,:source,:reports,:groups,:radius,:start,:end,:predicted,:top,:fine,:error,:now,:notify,:title,:body)
                ON CONFLICT (parking_event_id) DO UPDATE SET
                  status=EXCLUDED.status,level=EXCLUDED.level,prediction_status=EXCLUDED.prediction_status,
                  primary_zone_type=EXCLUDED.primary_zone_type,attempt_count=EXCLUDED.attempt_count,
                  reference_time=EXCLUDED.reference_time,weekday_actual=EXCLUDED.weekday_actual,weekday=EXCLUDED.weekday,
                  time_slot=EXCLUDED.time_slot,is_holiday=EXCLUDED.is_holiday,grid_id=EXCLUDED.grid_id,
                  zone_rule_set=EXCLUDED.zone_rule_set,prediction_version_id=EXCLUDED.prediction_version_id,
                  source_import_id=EXCLUDED.source_import_id,report_count=EXCLUDED.report_count,
                  event_group_count=EXCLUDED.event_group_count,report_count_radius_m=EXCLUDED.report_count_radius_m,
                  report_period_start=EXCLUDED.report_period_start,report_period_end=EXCLUDED.report_period_end,
                  predicted_count=EXCLUDED.predicted_count,top_percent=EXCLUDED.top_percent,
                  reference_fine_amount=EXCLUDED.reference_fine_amount,error_code=EXCLUDED.error_code,
                  evaluated_at=EXCLUDED.evaluated_at,updated_at=EXCLUDED.evaluated_at,
                  notify_decision=EXCLUDED.notify_decision,message_title=EXCLUDED.message_title,message_body=EXCLUDED.message_body,
                  delivered_at=NULL,delivery_failure=NULL
                """,values);
        int rank=0;
        for (var reason:reasons) {
            boolean model=reason.containsKey("model");
            var rp=new MapSqlParameterSource().addValue("id",UUID.randomUUID()).addValue("a",assessment).addValue("rank",++rank)
                    .addValue("type",model ? "MODEL" : "FACILITY_RULE").addValue("zone",reason.get("zone_type"))
                    .addValue("facility",reason.get("facility_id")).addValue("distance",reason.get("distance_m"))
                    .addValue("threshold",reason.get("threshold_m"))
                    .addValue("accuracy",model || request.originalLocation()==null ? null : request.originalLocation().accuracyM().doubleValue())
                    .addValue("description",model ? reason.get("description") : reason.get("label")+" 법정 거리 이내 (확정 좌표 기준)");
            store.jdbc.update("""
                    INSERT INTO public.assessment_reasons(id,assessment_id,rank,reason_type,zone_type,facility_id,distance_m,threshold_m,accuracy_margin_m,description)
                    VALUES (:id,:a,:rank,:type,:zone,:facility,:distance,:threshold,:accuracy,:description)
                    """,rp);
        }
    }

    static String modelDescription(int weekday,int slot,double top) {
        String day=List.of("월요일","화요일","수요일","목요일","금요일","토요일","일요일").get(weekday-1);
        return day+" "+slot*3+"~"+(slot+1)*3+"시 기준 신고 상위 "+java.math.BigDecimal.valueOf(top).stripTrailingZeros().toPlainString()+"%";
    }

    /** Separate read boundary also permits fault injection without altering protected frozen data. */
    protected List<Map<String,Object>> predictions(Map<String,Object> key) {
        return store.jdbc.queryForList("""
                SELECT smoothed_count,top_percent FROM public.risk_predictions
                WHERE version_id=:version AND grid_id=:grid AND weekday=:weekday AND time_slot=:slot
                """,key);
    }
}
