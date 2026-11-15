package io.github.yongyong14.parking_server.parking;

import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.context.annotation.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import io.github.yongyong14.parking_server.global.exception.GlobalExceptionHandler;
import io.github.yongyong14.parking_server.global.security.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Opt-in HTTP checks on an attested, freshly restored reference-only tmpfs DB. Never changes reference rows. */
@EnabledIfEnvironmentVariable(named="PARKING_PEAK_REFERENCE_JDBC_URL",matches=".+")
class ParkingPeakReferencePostgresTest {
    @Configuration @EnableTransactionManagement static class Transactions {}
    @Test void actualDumpPeaksMatchExactSourcePeriodForBothDemoPoints() throws Exception {
        String url=System.getenv("PARKING_PEAK_REFERENCE_JDBC_URL");
        if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:\\d+/parking_dump_trial")) throw new IllegalStateException("Expected isolated reference DB");
        var ownerSource=new DriverManagerDataSource(url,"parking_migrator","");
        var owner=new JdbcTemplate(ownerSource);
        assertThat(owner.queryForObject("SELECT current_database()||':'||current_setting('verification.identity',true)",String.class))
                .isEqualTo("parking_dump_trial:dump-trial-20261106");
        // Normal deployment path, only inside the isolated restored DB; no separate manual grant.
        org.flywaydb.core.Flyway.configure().dataSource(ownerSource).locations("classpath:db/migration")
                .defaultSchema("flyway").schemas("flyway").placeholderReplacement(false).cleanDisabled(true).load().migrate();
        var source=new DriverManagerDataSource(url,"parking_api","");
        var api=new JdbcTemplate(source);
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
        var report=new ArrayList<Map<String,Object>>();
        var periods=api.queryForList("SELECT import_id,period_start,period_end,count(*) AS rows FROM report_aggregates GROUP BY 1,2,3 ORDER BY 1,2,3");
        UUID user=UUID.randomUUID(),vehicle=UUID.randomUUID();
        var now=Instant.parse("2026-11-07T10:05:41Z");
        try (var context=new AnnotationConfigApplicationContext()) {
            context.register(Transactions.class);
            context.registerBean(DataSource.class,()->source);
            context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
            context.registerBean(Clock.class,()->Clock.fixed(now,ZoneOffset.UTC));
            context.registerBean(ParkingSettings.class,()->new ParkingSettings("",List.of(),Duration.ofMinutes(2),Duration.ofMinutes(10),10,50));
            context.register(ParkingStore.class,ParkingQueryTransactions.class,ParkingQueryService.class,ParkingDeliveryTransactions.class);
            context.refresh();
            var secret=new byte[32]; new java.security.SecureRandom().nextBytes(secret);
            var tokens=new AccessTokenService(new TokenSettings("isolated-peak",Base64.getEncoder().encodeToString(secret),Duration.ofMinutes(30),Duration.ofDays(30)),Clock.fixed(now,ZoneOffset.UTC));
            Arrays.fill(secret,(byte)0);
            var filter=new AccessTokenFilter(tokens,new JdbcUserExistence(org.springframework.jdbc.core.simple.JdbcClient.create(source)),new SecurityErrorWriter(mapper),request->false);
            var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                    new ParkingController(org.mockito.Mockito.mock(ParkingService.class),context.getBean(ParkingQueryService.class)))
                    .setControllerAdvice(new GlobalExceptionHandler()).setCustomArgumentResolvers(new CurrentUserResolver()).addFilters(filter).build();
            owner.update("INSERT INTO users(id,kakao_id) VALUES (?,?)",user,Math.abs(new Random().nextLong()));
            owner.update("INSERT INTO vehicles(id,user_id,name,bluetooth_address) VALUES (?,?,'reference fixture','AA:BB:CC:DD:EE:FF')",vehicle,user);
            String bearer="Bearer "+tokens.issue(user).accessToken();
            var versions=api.queryForList("SELECT id,model_version,source_import_id,training_start,observed_end,holiday_policy FROM prediction_versions ORDER BY model_version");
            assertThat(versions).isNotEmpty();
            int pointIndex=0;
            for (var point:List.of(List.of(37.324450,127.096184),List.of(37.324900,127.096749))) {
                pointIndex++;
                var grid=api.queryForMap("""
                        SELECT id FROM spatial_grids, (SELECT ST_Transform(ST_SetSRID(ST_MakePoint(?,?),4326),5186) p) t
                        WHERE projected_srid=5186 AND cell_size_m=50 AND grid_x=floor(ST_X(p)/50)::int AND grid_y=floor(ST_Y(p)/50)::int
                        """,point.get(1),point.get(0));
                for (var v:versions) {
                    // Verify all weekdays; Saturday is the established demo scenario.
                    for (int day=1;day<=7;day++) {
                        var detected=LocalDate.of(2026,11,2).plusDays(day-1).atTime(19,5,12).atZone(ZoneId.of("Asia/Seoul"));
                        boolean holiday=Boolean.TRUE.equals(api.queryForObject("SELECT EXISTS(SELECT 1 FROM holidays WHERE holiday_date=?)",Boolean.class,detected.toLocalDate()));
                        int weekday=holiday && "AS_SUNDAY".equals(v.get("holiday_policy")) ? 7 : day;
                        UUID event=UUID.randomUUID();
                        owner.update("""
                                INSERT INTO parking_events(id,user_id,vehicle_id,client_event_id,request_hash,vehicle_name_snapshot,detected_at,confirmed_at,confirmed_location,confirmation_method)
                                VALUES (?,?,?,?,?,'reference fixture',?,?,ST_SetSRID(ST_MakePoint(?,?),4326)::geography,'CONFIRMED')
                                """,event,user,vehicle,UUID.randomUUID(),"reference-fixture",Timestamp.from(detected.toInstant()),Timestamp.from(detected.plusSeconds(28).toInstant()),point.get(1),point.get(0));
                        owner.update("""
                                INSERT INTO risk_assessments(id,parking_event_id,status,prediction_status,prediction_version_id,source_import_id,is_holiday,notify_decision,evaluated_at)
                                VALUES (?,?,'FAILED','MISSING_ROW',?,?,?,'NONE',?)
                                """,UUID.randomUUID(),event,v.get("id"),v.get("source_import_id"),holiday,Timestamp.from(now));
                        var rows=api.queryForList("""
                                SELECT time_slot,report_count FROM report_aggregates WHERE import_id=? AND grid_id=? AND weekday=?
                                AND period_start=? AND period_end=? ORDER BY time_slot
                                """,v.get("source_import_id"),grid.get("id"),weekday,v.get("training_start"),v.get("observed_end"));
                        String response=mvc.perform(get("/api/v1/parking-events/"+event).header("Authorization",bearer)).andExpect(status().isOk())
                                .andReturn().getResponse().getContentAsString();
                        var actual=mapper.readTree(response).at("/data/assessment/stats/peak");
                        var item=new LinkedHashMap<String,Object>();
                        item.put("point",pointIndex); item.put("grid",grid.get("id")); item.put("model",v.get("model_version"));
                        item.put("source",v.get("source_import_id").toString()); item.put("start",v.get("training_start").toString()); item.put("end",v.get("observed_end").toString()); item.put("weekday",weekday);
                        if (rows.isEmpty()) {
                            assertThat(actual.isNull()).isTrue();
                            int matchingSource=api.queryForObject("SELECT count(*) FROM report_aggregates WHERE grid_id=? AND weekday=? AND import_id=?",Integer.class,grid.get("id"),weekday,v.get("source_import_id"));
                            int anySource=api.queryForObject("SELECT count(*) FROM report_aggregates WHERE grid_id=? AND weekday=?",Integer.class,grid.get("id"),weekday);
                            item.put("nullReason",matchingSource>0 ? "PERIOD_MISMATCH" : anySource>0 ? "SOURCE_MISMATCH" : "NO_GRID_WEEKDAY_ROWS");
                        } else {
                            var counts=new ArrayList<>(Collections.nCopies(8,0L));
                            for (var row:rows) counts.set(((Number)row.get("time_slot")).intValue(),((Number)row.get("report_count")).longValue());
                            int slot=0; for (int i=1;i<8;i++) if (counts.get(i)>counts.get(slot)) slot=i;
                            assertThat(actual.path("weekday").asInt()).isEqualTo(weekday); assertThat(actual.path("timeSlot").asInt()).isEqualTo(slot);
                            assertThat(actual.path("slotCounts").toString()).isEqualTo(mapper.writeValueAsString(counts));
                            item.put("slotCounts",counts); item.put("timeSlot",slot);
                        }
                        report.add(item);
                    }
                }
            }
        } finally { owner.update("DELETE FROM users WHERE id=?",user); }
        var output=new LinkedHashMap<String,Object>();
        output.put("aggregatePeriods",periods.stream().map(r->{var copy=new LinkedHashMap<String,Object>();r.forEach((k,v)->copy.put(k,v.toString()));return copy;}).toList());
        output.put("httpChecks",report);
        Files.createDirectories(Path.of("build/peak-reference"));
        Files.writeString(Path.of("build/peak-reference/http-results.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(output));
    }
}
