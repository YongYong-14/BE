package io.github.yongyong14.parking_server.parking;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import io.github.yongyong14.parking_server.global.exception.*;
import io.github.yongyong14.parking_server.global.security.AgreementPolicy;

@Repository
@Lazy
public class ParkingStore {
    final NamedParameterJdbcTemplate jdbc;
    public ParkingStore(DataSource source) { jdbc = new NamedParameterJdbcTemplate(source); }
    void lockUserAndConsent(UUID user, AgreementPolicy policy) {
        if (jdbc.queryForList("SELECT id FROM public.users WHERE id=:u FOR SHARE", Map.of("u", user)).isEmpty())
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        var agreements = jdbc.queryForList("""
                SELECT action,version FROM public.user_agreements
                WHERE user_id=:u AND agreement_type='LOCATION_SERVICE' ORDER BY id DESC LIMIT 1
                """, Map.of("u", user));
        if (agreements.isEmpty() || !"AGREE".equals(agreements.getFirst().get("action"))
                || !policy.acceptsLocationVersion((String) agreements.getFirst().get("version")))
            throw new BusinessException(ErrorCode.LOCATION_CONSENT_REQUIRED);
    }
    UUID existing(UUID user, UUID key) {
        var rows = jdbc.queryForList("SELECT id FROM public.parking_events WHERE user_id=:u AND client_event_id=:k",
                Map.of("u", user, "k", key));
        return rows.isEmpty() ? null : (UUID) rows.getFirst().get("id");
    }
    Map<String,Object> locked(UUID user, UUID key) {
        return jdbc.queryForMap("""
                SELECT id,request_hash,detected_at,address FROM public.parking_events
                WHERE user_id=:u AND client_event_id=:k FOR UPDATE
                """, Map.of("u",user,"k",key));
    }
    String activeVehicle(UUID user, UUID vehicle) {
        var rows = jdbc.queryForList("""
                SELECT name FROM public.vehicles WHERE id=:v AND user_id=:u AND deleted_at IS NULL FOR SHARE
                """, Map.of("u",user,"v",vehicle));
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.VEHICLE_NOT_FOUND);
        return (String) rows.getFirst().get("name");
    }
    MapSqlParameterSource requestParams(ParkingRequest r) {
        return new MapSqlParameterSource()
                .addValue("lat",r.confirmedLocation().lat()).addValue("lng",r.confirmedLocation().lng())
                .addValue("olat",r.originalLocation()==null ? null : r.originalLocation().lat())
                .addValue("olng",r.originalLocation()==null ? null : r.originalLocation().lng())
                .addValue("llat",r.lastKnownLocation()==null ? null : r.lastKnownLocation().lat())
                .addValue("llng",r.lastKnownLocation()==null ? null : r.lastKnownLocation().lng());
    }
    void validateDistance(ParkingRequest r) {
        if (r.originalLocation()==null && r.lastKnownLocation()==null) return;
        boolean within = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT ST_DWithin(
                    ST_SetSRID(ST_MakePoint(CAST(COALESCE(:olng,:llng) AS float8),CAST(COALESCE(:olat,:llat) AS float8)),4326)::geography,
                    ST_SetSRID(ST_MakePoint(CAST(:lng AS float8),CAST(:lat AS float8)),4326)::geography,200)
                """,requestParams(r),Boolean.class));
        if (!within) throw new BusinessException(ErrorCode.ADJUST_DISTANCE_EXCEEDED);
    }
    UUID insert(UUID user, ParkingRequest r, String hash, String name) {
        UUID id=UUID.randomUUID();
        var p=requestParams(r).addValue("id",id).addValue("u",user).addValue("v",r.vehicleId())
                .addValue("k",r.clientEventId()).addValue("hash",hash).addValue("name",name)
                .addValue("detected",Timestamp.from(r.detectedAt().toInstant()))
                .addValue("confirmed",Timestamp.from(r.confirmedAt().toInstant()))
                .addValue("lastAt",r.lastKnownLocation()==null ? null : Timestamp.from(r.lastKnownLocation().at().toInstant()))
                .addValue("accuracy",r.originalLocation()==null ? null : r.originalLocation().accuracyM().doubleValue())
                .addValue("count",r.remeasureCount()).addValue("method",r.confirmationMethod().name());
        int inserted=jdbc.update("""
                INSERT INTO public.parking_events(id,user_id,vehicle_id,client_event_id,request_hash,vehicle_name_snapshot,
                  detected_at,confirmed_at,last_known_location,last_known_at,original_location,accuracy_m,
                  remeasure_count,confirmed_location,confirmation_method)
                VALUES (:id,:u,:v,:k,:hash,:name,:detected,:confirmed,
                  CASE WHEN CAST(:llat AS float8) IS NULL THEN NULL ELSE ST_SetSRID(ST_MakePoint(CAST(:llng AS float8),CAST(:llat AS float8)),4326)::geography END,:lastAt,
                  CASE WHEN CAST(:olat AS float8) IS NULL THEN NULL ELSE ST_SetSRID(ST_MakePoint(CAST(:olng AS float8),CAST(:olat AS float8)),4326)::geography END,:accuracy,
                  :count,ST_SetSRID(ST_MakePoint(CAST(:lng AS float8),CAST(:lat AS float8)),4326)::geography,:method)
                ON CONFLICT (user_id,client_event_id) DO NOTHING
                """,p);
        return inserted==1 ? id : null;
    }
    Map<String,Object> assessment(UUID event) {
        return jdbc.queryForMap("SELECT * FROM public.risk_assessments WHERE parking_event_id=:id",Map.of("id",event));
    }
    List<Map<String,Object>> reasons(UUID assessment) {
        return jdbc.queryForList("SELECT * FROM public.assessment_reasons WHERE assessment_id=:id ORDER BY rank",Map.of("id",assessment));
    }
    Map<String,Object> event(UUID user, UUID id) {
        return jdbc.queryForMap("SELECT id,detected_at,address FROM public.parking_events WHERE id=:id AND user_id=:u",Map.of("u",user,"id",id));
    }
    Map<String,Object> notificationSettings(UUID user) {
        // Account signup owns row creation. Missing rows are an invariant failure, never guessed defaults.
        return jdbc.queryForMap("SELECT caution_enabled,night_enabled FROM public.notification_settings WHERE user_id=:u",Map.of("u",user));
    }
    void logUse(UUID user, UUID event) {
        jdbc.update("""
                INSERT INTO public.location_access_logs(user_id,parking_event_id,action,purpose,collection_method)
                VALUES (:u,:e,'USE','주차 위험 판정','단말 확정 좌표')
                """,Map.of("u",user,"e",event));
    }
    static Instant instant(Object timestamp) { return ((Timestamp)timestamp).toInstant(); }
}
