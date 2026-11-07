package io.github.yongyong14.parking_server.map;

import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import io.github.yongyong14.parking_server.global.exception.*;

@Repository
@Lazy
public class MapRiskRepository {
    // One definition for both spatial queries, including zero-width/height viewports.
    static final String VIEWPORT = """
        CASE WHEN :minLat=:maxLat AND :minLng=:maxLng
          THEN ST_SetSRID(ST_MakePoint(:minLng,:minLat),4326)
        WHEN :minLat=:maxLat OR :minLng=:maxLng
          THEN ST_SetSRID(ST_MakeLine(ST_MakePoint(:minLng,:minLat),ST_MakePoint(:maxLng,:maxLat)),4326)
        ELSE ST_MakeEnvelope(:minLng,:minLat,:maxLng,:maxLat,4326) END
        """;
    // ST_DWithin supplies a radius-expanded GiST prefilter on the unmodified geography column.
    // A center-only && filter would incorrectly discard facilities just outside the viewport.
    static final String FACILITIES_SQL = "WITH viewport AS (SELECT " + VIEWPORT + " AS area) " + """
            SELECT f.zone_type,ST_Y(f.spatial_data::geometry) lat,ST_X(f.spatial_data::geometry) lng,
                   r.legal_distance_m
            FROM viewport v CROSS JOIN public.facilities f JOIN public.data_imports i ON i.id=f.import_id AND i.active
            JOIN public.zone_rules r ON r.zone_type=f.zone_type AND r.rule_set=:rules
            WHERE f.zone_type IN ('HYDRANT','BUS_STOP')
              AND ST_DWithin(f.spatial_data,v.area::geography,r.legal_distance_m)
            ORDER BY f.zone_type,f.id
            """;
    static final String PREDICTIONS_SQL = "WITH viewport AS (SELECT " + VIEWPORT + " AS area) " + """
            SELECT g.id,ST_Y(ST_Centroid(g.boundary)) lat,ST_X(ST_Centroid(g.boundary)) lng,
                   g.cell_size_m,p.top_percent,p.smoothed_count,
                   CASE WHEN p.top_percent <= :threshold THEN (
                     SELECT sum(s.report_count)::bigint FROM public.parcel_report_stats s
                     WHERE s.import_id=:source AND s.period_start=:start AND s.period_end=:end
                       AND s.location && g.boundary::geography
                       AND floor(ST_X(ST_Transform(s.location::geometry,:srid))/:size)=g.grid_x
                       AND floor(ST_Y(ST_Transform(s.location::geometry,:srid))/:size)=g.grid_y
                   ) END reports
            FROM viewport v CROSS JOIN public.prediction_target_grids t JOIN public.spatial_grids g ON g.id=t.grid_id
            LEFT JOIN public.risk_predictions p ON p.version_id=t.version_id AND p.grid_id=t.grid_id
              AND p.weekday=:weekday AND p.time_slot=:slot
            WHERE t.version_id=:version AND g.in_model_area AND g.projected_srid=:srid AND g.cell_size_m=:size
              AND g.boundary && v.area
              AND ST_Intersects(g.boundary,v.area)
            ORDER BY g.id
            """;

    private final JdbcClient jdbc;
    public MapRiskRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    record Version(UUID id, UUID source, LocalDate start, LocalDate end, String policy,
                   double threshold, int srid, int size) {}
    record Prediction(long gridId, MapRiskResponse.Location location, int size,
                      Double topPercent, Long reports) {}

    private JdbcClient.StatementSpec bounds(String sql, MapBounds b) {
        return jdbc.sql(sql).param("minLat", b.minLat()).param("minLng", b.minLng())
                .param("maxLat", b.maxLat()).param("maxLng", b.maxLng());
    }
    // Split longitude spans so a wrapped shortest path cannot accept a world-wide viewport.
    boolean withinLimit(MapBounds b) {
        return bounds("""
            SELECT greatest(
              (SELECT sum(ST_Distance(
                ST_SetSRID(ST_MakePoint(:minLng+(:maxLng-:minLng)*k/4.0,:minLat),4326)::geography,
                ST_SetSRID(ST_MakePoint(:minLng+(:maxLng-:minLng)*(k+1)/4.0,:minLat),4326)::geography))
                FROM generate_series(0,3) k),
              (SELECT sum(ST_Distance(
                ST_SetSRID(ST_MakePoint(:minLng+(:maxLng-:minLng)*k/4.0,:maxLat),4326)::geography,
                ST_SetSRID(ST_MakePoint(:minLng+(:maxLng-:minLng)*(k+1)/4.0,:maxLat),4326)::geography))
                FROM generate_series(0,3) k),
              ST_Distance(ST_SetSRID(ST_MakePoint(:minLng,:minLat),4326)::geography,
                          ST_SetSRID(ST_MakePoint(:minLng,:maxLat),4326)::geography)) <= 2000
            """, b).query(Boolean.class).single();
    }
    void requireRules(String rules, Instant instant) {
        boolean valid = jdbc.sql("""
            SELECT EXISTS (SELECT 1 FROM public.zone_rule_sets s WHERE s.rule_set=:rules
              AND s.published_at IS NOT NULL AND s.published_at <= :now)
            """).param("rules", rules).param("now", OffsetDateTime.ofInstant(instant, ZoneOffset.UTC))
                .query(Boolean.class).single();
        if (!valid) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        MapRuleContract.validate(jdbc.sql("""
            SELECT zone_type,geometry_rule,legal_distance_m,fine_amount_car FROM public.zone_rules
            WHERE rule_set=:rules AND zone_type IN ('HYDRANT','BUS_STOP','OTHER')
            """).param("rules",rules).query().listOfRows());
    }
    Optional<Version> active() {
        return jdbc.sql("""
            SELECT id,source_import_id,training_start,observed_end,holiday_policy,caution_top_percent,
                   projected_srid,cell_size_m, validated_at,frozen_at,loaded_row_count,target_cell_count,
                   tie_method,percentile_basis
            FROM public.prediction_versions WHERE active
            """).query((rs,n) -> {
                if (rs.getObject("validated_at")==null || rs.getObject("frozen_at")==null
                        || rs.getLong("loaded_row_count") != rs.getLong("target_cell_count")*56
                        || !Double.isFinite(rs.getDouble("caution_top_percent"))
                        || rs.getDouble("caution_top_percent")<0 || rs.getDouble("caution_top_percent")>100
                        || !"GE_SHARE".equals(rs.getString("tie_method"))
                        || !Set.of("SAME_SLOT","GLOBAL").contains(rs.getString("percentile_basis")))
                    throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
                return new Version(rs.getObject("id",UUID.class),rs.getObject("source_import_id",UUID.class),
                        rs.getObject("training_start",LocalDate.class),rs.getObject("observed_end",LocalDate.class),
                        rs.getString("holiday_policy"),rs.getDouble("caution_top_percent"),
                        rs.getInt("projected_srid"),rs.getInt("cell_size_m"));
            }).optional();
    }
    boolean holiday(LocalDate date) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM public.holidays WHERE holiday_date=:date)")
                .param("date",date).query(Boolean.class).single();
    }
    void requireStatistics(Version v) {
        boolean ready=jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM public.parcel_report_stats
              WHERE import_id=:source AND period_start=:start AND period_end=:end)
            """).param("source",v.source()).param("start",v.start()).param("end",v.end())
                .query(Boolean.class).single();
        if (!ready) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
    }
    List<MapRiskResponse.FacilityZone> facilities(MapBounds b, String rules) {
        return bounds(FACILITIES_SQL,b).param("rules",rules).query((rs,n) -> new MapRiskResponse.FacilityZone(
                rs.getString("zone_type"),new MapRiskResponse.Location(rs.getDouble("lat"),rs.getDouble("lng")),
                rs.getDouble("legal_distance_m"))).list();
    }
    List<Prediction> predictions(MapBounds b, Version v, MapRiskResponse.Slot slot) {
        return bounds(PREDICTIONS_SQL,b).param("threshold",v.threshold()).param("source",v.source()).param("start",v.start())
                .param("end",v.end()).param("srid",v.srid()).param("size",v.size())
                .param("weekday",slot.weekday()).param("slot",slot.timeSlot()).param("version",v.id())
                .query((rs,n) -> {
                    Double top=rs.getObject("top_percent",Double.class);
                    Double smoothed=rs.getObject("smoothed_count",Double.class);
                    if(top!=null && (smoothed==null || !Double.isFinite(smoothed) || smoothed<0))
                        throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
                    return new Prediction(rs.getLong("id"),new MapRiskResponse.Location(
                        rs.getDouble("lat"),rs.getDouble("lng")),rs.getInt("cell_size_m"),
                        top,rs.getObject("reports",Long.class));
                }).list();
    }
}
