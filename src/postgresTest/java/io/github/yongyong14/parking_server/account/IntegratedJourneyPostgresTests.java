package io.github.yongyong14.parking_server.account;

import io.github.yongyong14.parking_server.parking.ReverseGeocoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Only the external Kakao boundary is replaced. Security, JPA, JDBC, transactions and events are real. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=false",
        "app.auth.issuer=isolated-journey", "app.auth.secret-base64=${random.value}${random.value}",
        "app.account.terms-versions=terms-current", "app.account.privacy-versions=privacy-current",
        "app.parking.rule-set=yongin-2026-11-v1", "app.parking.facility-priority=HYDRANT,BUS_STOP",
        "logging.level.root=WARN", "logging.level.org.springframework.test.web.servlet=OFF",
        "logging.level.org.springframework.boot.security=OFF",
        "spring.jpa.open-in-view=false", "spring.jpa.show-sql=false"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@EntityScan(basePackageClasses={User.class,io.github.yongyong14.parking_server.vehicle.Vehicle.class})
@Import(IntegratedJourneyPostgresTests.Time.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IntegratedJourneyPostgresTests {
    static final Instant NOW=Instant.parse("2026-11-07T10:05:41Z");
    static final UUID BASE=UUID.fromString("00000000-0000-0000-0000-000000000021");
    static final UUID NEXT=UUID.fromString("00000000-0000-0000-0000-000000000022");
    static final AtomicLong IDS=new AtomicLong(7000);
    @TestConfiguration static class Time {
        @Bean @Primary Clock journeyClock() { return Clock.fixed(NOW,ZoneOffset.UTC); }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DataSource runtime;
    @MockitoBean KakaoClient kakao;
    @MockitoBean ReverseGeocoder geocoder;
    JdbcTemplate owner,api;

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        String url=required("PARKING_TEST_JDBC_URL"), password=required("PARKING_TEST_PASSWORD");
        if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:\\d+/parking_event_verify"))
            throw new IllegalStateException("Expected fresh isolated parking_event_verify DB");
        for(String role:List.of("parking_migrator","parking_api")) {
            var db=new JdbcTemplate(source(url,role,password));
            if(!("parking_event_verify:"+role+":"+required("PARKING_TEST_IDENTITY")).equals(db.queryForObject(
                    "SELECT current_database()||':'||current_user||':'||current_setting('verification.identity',true)",String.class)))
                throw new IllegalStateException("Isolated database identity/role mismatch");
        }
        Flyway.configure().dataSource(source(url,"parking_migrator",password)).defaultSchema("flyway")
                .schemas("flyway").placeholderReplacement(false).cleanDisabled(true).load().migrate();
        properties.add("spring.datasource.url",()->url);
        properties.add("spring.datasource.username",()->"parking_api");
        properties.add("spring.datasource.password",()->password);
        // Both modules resolve this same production property through application.yaml.
        properties.add("app.account.location-versions",()->required("AUTH_LOCATION_VERSIONS")+",location-previous");
    }
    static DriverManagerDataSource source(String url,String role,String password) {
        var source=new DriverManagerDataSource(url,role,password);
        var properties=new Properties(); properties.setProperty("logServerErrorDetail","false");
        source.setConnectionProperties(properties); return source;
    }
    static String required(String key) {
        String value=System.getenv(key);
        if(value==null||value.isBlank()) throw new IllegalStateException("Use Verify-ParkingEvents.ps1 -Integration");
        return value;
    }
    @BeforeAll void references() throws Exception {
        owner=new JdbcTemplate(source(required("PARKING_TEST_JDBC_URL"),"parking_migrator",required("PARKING_TEST_PASSWORD")));
        api=new JdbcTemplate(runtime);
        assertEquals("parking_api",api.queryForObject("SELECT current_user",String.class));
        owner.execute("INSERT INTO zone_types VALUES ('HYDRANT','hydrant',true),('BUS_STOP','bus',true),('OTHER','other',false)");
        owner.execute(Files.readString(Path.of("docs/rules_v1.sql")));
        owner.execute("UPDATE zone_rule_sets SET published_at='2026-11-01T00:00:00Z' WHERE rule_set='yongin-2026-11-v1'");
        owner.execute(new ClassPathResource("map-fixture.sql").getContentAsString(StandardCharsets.UTF_8));
        owner.execute("UPDATE data_imports SET source_dataset='yongin_enforcement' WHERE id='00000000-0000-0000-0000-000000000002'");
        owner.execute("""
            INSERT INTO report_aggregates(import_id,grid_id,weekday,time_slot,period_start,period_end,day_count,report_count,event_group_count)
            SELECT '00000000-0000-0000-0000-000000000002',1,7,s,'2025-01-01','2025-09-25',1,s+1,1 FROM generate_series(0,7) s;
            INSERT INTO report_aggregates(import_id,grid_id,weekday,time_slot,period_start,period_end,day_count,report_count,event_group_count)
            VALUES ('00000000-0000-0000-0000-000000000002',1,7,0,'2024-01-01','2024-09-25',1,999,1);
            INSERT INTO prediction_versions(id,model_type,model_version,training_start,training_end,target_definition,
              source_import_id,observed_end,holiday_policy,smoothing_radius_m,percentile_basis,caution_top_percent,target_cell_count)
            VALUES ('00000000-0000-0000-0000-000000000022','BASELINE','journey-next','2024-01-01','2024-08-31','REPORT_COUNT',
              '00000000-0000-0000-0000-000000000002','2024-09-25','AS_SUNDAY',50,'SAME_SLOT',8.125,1);
            INSERT INTO prediction_target_grids VALUES ('00000000-0000-0000-0000-000000000022',1);
            INSERT INTO risk_predictions(version_id,grid_id,weekday,time_slot,expected_count,smoothed_count,top_percent)
            SELECT '00000000-0000-0000-0000-000000000022',1,w,s,1,1,70 FROM generate_series(1,7) w CROSS JOIN generate_series(0,7) s;
            SELECT freeze_prediction_version('00000000-0000-0000-0000-000000000022');
            """);
    }
    @BeforeEach void resetFixtures() {
        owner.execute("DELETE FROM users; DELETE FROM facilities; UPDATE data_imports SET active=true WHERE id='00000000-0000-0000-0000-000000000002'");
        owner.queryForObject("SELECT activate_prediction_version(?)",Object.class,BASE);
        when(geocoder.enabled()).thenReturn(false);
    }
    record Result(int status,JsonNode document,String body) { JsonNode data() { return document.path("data"); } }
    Result request(MockHttpServletRequestBuilder builder,JsonNode account,int expected) throws Exception {
        if(account!=null) builder.header("Authorization","Bearer "+account.path("accessToken").asText());
        var response=mvc.perform(builder).andReturn().getResponse();
        // Never print a request/response on failure: they can contain credentials and coordinates.
        if(expected==0) assertTrue(response.getStatus()==200 || response.getStatus()==201,"Concurrent save status (payload suppressed)");
        else assertEquals(expected,response.getStatus(),"HTTP status (payload suppressed)");
        String body=response.getContentAsString();
        return new Result(response.getStatus(),body.isEmpty()?json.nullNode():json.readTree(body),body);
    }
    MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder builder,Object value) {
        return builder.contentType("application/json").content(json.writeValueAsString(value));
    }
    JsonNode login() throws Exception {
        long id=IDS.incrementAndGet(); String token="external-test-"+id;
        when(kakao.verify(token)).thenReturn(new KakaoClient.Profile(id,"journey",null,null));
        var account=request(body(post("/api/v1/auth/kakao"),Map.of("kakaoAccessToken",token)),null,200).data();
        assertTrue(account.path("isNewUser").asBoolean());
        assertTrue(account.path("accessToken").isTextual());
        return account;
    }
    void consent(JsonNode account,String version,int expected) throws Exception {
        request(body(post("/api/v1/me/agreements"),Map.of("type","LOCATION_SERVICE","version",version,"action","AGREE")),account,expected);
    }
    JsonNode ready() throws Exception {
        var account=login(); coreAgreements(account); consent(account,required("AUTH_LOCATION_VERSIONS"),201); return account;
    }
    void coreAgreements(JsonNode account) throws Exception {
        for(var entry:Map.of("TERMS","terms-current","PRIVACY","privacy-current").entrySet())
            request(body(post("/api/v1/me/agreements"),Map.of("type",entry.getKey(),"version",entry.getValue(),"action","AGREE")),account,201);
    }
    String vehicle(JsonNode account,String mac,String name) throws Exception {
        return request(body(put("/api/v1/vehicle"),Map.of("name",name,"bluetoothAddress",mac)),account,200)
                .data().at("/vehicle/id").asText();
    }
    Map<String,Object> parking(String vehicle) {
        return new LinkedHashMap<>(Map.of("clientEventId",UUID.randomUUID().toString(),"vehicleId",vehicle,
                "detectedAt","2026-11-07T19:05:12+09:00","confirmedAt","2026-11-07T19:05:40+09:00",
                "confirmationMethod","CONFIRMED","confirmedLocation",Map.of("lat",37.3205,"lng",127.0905),"remeasureCount",0));
    }
    JsonNode save(JsonNode account,Map<String,Object> payload,int status) throws Exception {
        return request(body(post("/api/v1/parking-events"),payload),account,status).data();
    }
    JsonNode detail(JsonNode account,String id) throws Exception {
        return request(get("/api/v1/parking-events/"+id),account,200).data();
    }
    JsonNode assessment(JsonNode detail) {
        var copy=detail.path("assessment").deepCopy();
        ((tools.jackson.databind.node.ObjectNode)copy.path("stats")).remove("peak"); return copy;
    }
    @Test void accountConsentVehicleSnapshotReplayAndUserIsolation() throws Exception {
        var account=login();
        assertTrue(request(get("/api/v1/vehicle"),account,200).data().path("vehicle").isNull());
        var absent=parking(UUID.randomUUID().toString());
        request(body(post("/api/v1/parking-events"),absent),account,403);
        consent(account,"location-previous",201);
        assertTrue(request(get("/api/v1/me"),account,200).data().path("agreements").size()>0);
        request(body(post("/api/v1/parking-events"),absent),account,403);
        consent(account,required("AUTH_LOCATION_VERSIONS"),201);
        coreAgreements(account);
        assertEquals(3,request(get("/api/v1/me"),account,200).data().path("agreements").size());
        request(body(post("/api/v1/parking-events"),absent),account,404);
        String original=vehicle(account,"AA:BB:CC:DD:EE:01","original");
        var payload=parking(original); var saved=save(account,payload,201); String event=saved.path("parkingEventId").asText();
        assertEquals("SUCCEEDED",saved.at("/assessment/status").asText());
        assertEquals(2,saved.at("/assessment/level").asInt());
        assertEquals(saved.path("assessment"),assessment(detail(account,event)));
        assertEquals(original,vehicle(account,"AA:BB:CC:DD:EE:01","renamed"));
        assertFalse(original.equals(vehicle(account,"AA:BB:CC:DD:EE:02","replacement")));
        assertEquals(saved.path("assessment"),save(account,payload,200).path("assessment"));
        assertEquals("original",detail(account,event).at("/event/vehicleName").asText());
        var changed=new LinkedHashMap<>(payload); changed.put("remeasureCount",1);
        request(body(post("/api/v1/parking-events"),changed),account,409);
        var latest=request(get("/api/v1/parking-events/latest"),account,200).data();
        assertEquals(event,latest.at("/event/id").asText());
        assertEquals(saved.at("/assessment/status"),latest.at("/assessment/status"));
        var items=request(get("/api/v1/parking-events"),account,200).data().path("items");
        assertEquals(1,items.size()); assertEquals(saved.at("/assessment/level"),items.get(0).at("/assessment/level"));
        var stranger=ready();
        request(get("/api/v1/parking-events/"+event),stranger,404);
        request(body(patch("/api/v1/parking-events/"+event+"/delivery"),Map.of("failure","UNKNOWN")),stranger,404);
        assertTrue(request(get("/api/v1/parking-events"),stranger,200).data().path("items").isEmpty());
        request(body(post("/api/v1/me/agreements"),Map.of("type","LOCATION_SERVICE","version",required("AUTH_LOCATION_VERSIONS"),"action","WITHDRAW")),account,503);
        assertEquals(saved.path("assessment"),assessment(detail(account,event)));
    }
    @Test void savedModelPeakMapAndVersionSwitchUseTheCorrectReferences() throws Exception {
        var account=ready(); var payload=parking(vehicle(account,"AA:BB:CC:DD:EE:03","model"));
        var saved=save(account,payload,201); String event=saved.path("parkingEventId").asText();
        var oldDetail=detail(account,event);
        var expected=api.queryForList("SELECT report_count FROM report_aggregates WHERE import_id='00000000-0000-0000-0000-000000000002' AND grid_id=1 AND weekday=7 AND period_start='2025-01-01' AND period_end='2025-09-25' ORDER BY time_slot",Long.class);
        var actualCounts=new ArrayList<Long>();
        oldDetail.at("/assessment/stats/peak/slotCounts").forEach(count->actualCounts.add(count.asLong()));
        assertEquals(expected,actualCounts);
        assertEquals(7,oldDetail.at("/assessment/stats/peak/timeSlot").asInt());
        var map=map(account); assertEquals(7,map.at("/slot/weekday").asInt()); assertEquals(6,map.at("/slot/timeSlot").asInt());
        double raw=api.queryForObject("SELECT top_percent FROM risk_predictions WHERE version_id=? AND grid_id=1 AND weekday=7 AND time_slot=6",Double.class,BASE);
        assertEquals(raw,saved.at("/assessment/stats/topPercent").asDouble());
        assertEquals(raw,map.at("/cells/0/topPercent").asDouble());
        assertEquals(12,map.at("/cells/0/reportCount").asInt());
        owner.queryForObject("SELECT activate_prediction_version(?)",Object.class,NEXT);
        assertTrue(map(account).path("cells").isEmpty());
        var latest=request(get("/api/v1/parking-events/latest"),account,200).data();
        assertEquals("2024-01-01",latest.at("/dataPeriod/start").asText());
        assertEquals(oldDetail.path("assessment"),detail(account,event).path("assessment"));
        assertEquals(saved.path("assessment"),save(account,payload,200).path("assessment"));
        payload.put("clientEventId",UUID.randomUUID().toString());
        var next=save(account,payload,201);
        assertEquals(3,next.at("/assessment/level").asInt());
        assertEquals(70,next.at("/assessment/stats/topPercent").asDouble());
        // A facility rule takes priority over the very same non-caution grid prediction.
        owner.execute("INSERT INTO facilities(id,import_id,source_record_key,zone_type,spatial_data) VALUES (gen_random_uuid(),'00000000-0000-0000-0000-000000000001','journey','HYDRANT',ST_SetSRID(ST_MakePoint(127.0905,37.3205),4326))");
        payload.put("clientEventId",UUID.randomUUID().toString()); var facility=save(account,payload,201);
        assertEquals(1,facility.at("/assessment/level").asInt());
        assertEquals("SKIPPED",facility.at("/assessment/predictionStatus").asText());
        assertEquals(1,map(account).path("facilityZones").size()); assertTrue(map(account).path("cells").isEmpty());
    }
    JsonNode map(JsonNode account) throws Exception {
        return request(get("/api/v1/map/risk-zones").param("minLat","37.3204").param("maxLat","37.3206")
                .param("minLng","127.0904").param("maxLng","127.0906").param("at","2026-11-07T19:05:12+09:00"),account,200).data();
    }
    @Test void concurrentSavesAndDeliveryPreserveFirstSuccessAndImmutableAssessment() throws Exception {
        var account=ready(); var payload=parking(vehicle(account,"AA:BB:CC:DD:EE:04","concurrent"));
        var results=concurrent(()->request(body(post("/api/v1/parking-events"),payload),account,0));
        // Concurrent helper below permits 200/201 without ever emitting the sensitive body.
        assertEquals(Set.of(200,201),new HashSet<>(results.stream().map(Result::status).toList()));
        String event=results.getFirst().data().path("parkingEventId").asText();
        assertEquals(event,results.getLast().data().path("parkingEventId").asText());
        var saved=results.getFirst().data().path("assessment");
        assertEquals(saved,results.getLast().data().path("assessment"));
        assertEquals("SEND",saved.at("/notify/decision").asText());
        String path="/api/v1/parking-events/"+event+"/delivery";
        var failures=concurrent(()->request(body(patch(path),Map.of("failure","NO_PERMISSION")),account,204));
        assertTrue(failures.stream().allMatch(r->r.body().isEmpty()));
        request(body(patch(path),Map.of("deliveredAt","2026-11-07T19:05:41+09:00")),account,204);
        var first=api.queryForMap("SELECT delivered_at,delivery_failure FROM risk_assessments WHERE parking_event_id=?",UUID.fromString(event));
        concurrent(()->request(body(patch(path),Map.of("deliveredAt","2026-11-07T19:05:42+09:00")),account,204));
        request(body(patch(path),Map.of("failure","DISPLAY_FAILED")),account,204);
        request(body(patch(path),Map.of("deliveredAt","invalid","failure","UNKNOWN")),account,400);
        assertEquals(first,api.queryForMap("SELECT delivered_at,delivery_failure FROM risk_assessments WHERE parking_event_id=?",UUID.fromString(event)));
        assertEquals(saved,assessment(detail(account,event)));
        owner.queryForObject("SELECT activate_prediction_version(?)",Object.class,NEXT);
        payload.put("clientEventId",UUID.randomUUID().toString()); var silent=save(account,payload,201);
        request(body(patch("/api/v1/parking-events/"+silent.path("parkingEventId").asText()+"/delivery"),Map.of("failure","UNKNOWN")),account,409);
    }
    @Test void partialFailedAndPostCommitAddressNeverBecomeSafeOrBreakReplay() throws Exception {
        var account=ready(); var payload=parking(vehicle(account,"AA:BB:CC:DD:EE:05","address"));
        when(geocoder.enabled()).thenReturn(true);
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(geocoder.address(any(),any())).thenAnswer(call->{ entered.countDown(); if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Address test timeout"); return Optional.of("synthetic address"); });
        JsonNode saved;
        try { saved=save(account,payload,201); assertTrue(entered.await(5,TimeUnit.SECONDS)); }
        finally { release.countDown(); }
        String event=saved.path("parkingEventId").asText();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(api.queryForObject("SELECT address IS NULL FROM parking_events WHERE id=?",Boolean.class,UUID.fromString(event)) && System.nanoTime()<deadline) Thread.sleep(20);
        assertEquals("synthetic address",detail(account,event).at("/event/address").asText());
        assertEquals(saved.path("assessment"),save(account,payload,200).path("assessment"));
        assertEquals(saved.path("assessment"),assessment(detail(account,event)));
        assertEquals(1,api.queryForObject("SELECT count(*) FROM location_access_logs WHERE parking_event_id=? AND action='PROVIDE'",Integer.class,UUID.fromString(event)));
        when(geocoder.enabled()).thenReturn(false);
        owner.execute("UPDATE prediction_versions SET active=false WHERE active");
        payload.put("clientEventId",UUID.randomUUID().toString()); var partial=save(account,payload,201);
        assertEquals("PARTIAL",partial.at("/assessment/status").asText()); assertTrue(partial.at("/assessment/level").isNull());
        assertEquals(partial.path("assessment"),assessment(detail(account,partial.path("parkingEventId").asText())));
        var partialLatest=request(get("/api/v1/parking-events/latest"),account,200).data();
        // Several events can share a detectedAt; list assertions use the matching ID, not UUID ordering.
        var partialItems=request(get("/api/v1/parking-events"),account,200).data().path("items");
        boolean sawPartial=false;
        for(var item:partialItems) if(item.at("/event/id").asText().equals(partial.path("parkingEventId").asText())) {
            assertEquals("PARTIAL",item.at("/assessment/status").asText()); assertTrue(item.at("/assessment/level").isNull()); sawPartial=true;
        }
        assertTrue(sawPartial); assertTrue(partialLatest.path("dataPeriod").isNull());
        assertEquals(partial.path("assessment"),save(account,payload,200).path("assessment"));
        owner.queryForObject("SELECT activate_prediction_version(?)",Object.class,BASE);
        owner.execute("UPDATE data_imports SET active=false WHERE source_dataset='yongin_enforcement'");
        payload.put("clientEventId",UUID.randomUUID().toString()); var failed=save(account,payload,201);
        assertEquals("FAILED",failed.at("/assessment/status").asText()); assertTrue(failed.at("/assessment/level").isNull());
        assertEquals("FAILED",detail(account,failed.path("parkingEventId").asText()).at("/assessment/status").asText());
        var retry=save(account,payload,200);
        assertEquals("FAILED",retry.at("/assessment/status").asText()); assertEquals(2,retry.at("/assessment/attemptCount").asInt());
        assertEquals(retry.path("assessment"),save(account,payload,200).path("assessment"));
        boolean sawFailed=false;
        for(var item:request(get("/api/v1/parking-events"),account,200).data().path("items"))
            if(item.at("/event/id").asText().equals(failed.path("parkingEventId").asText())) {
                assertEquals("FAILED",item.at("/assessment/status").asText()); assertTrue(item.at("/assessment/level").isNull()); sawFailed=true;
            }
        assertTrue(sawFailed);
    }
    @Test void refreshReplayLogoutAndDeletionApplyAcrossAllModules() throws Exception {
        var account=ready(); var payload=parking(vehicle(account,"AA:BB:CC:DD:EE:06","lifecycle"));
        var saved=save(account,payload,201); String event=saved.path("parkingEventId").asText();
        String refresh=account.path("refreshToken").asText();
        var next=request(body(post("/api/v1/auth/refresh"),Map.of("refreshToken",refresh)),null,200).data();
        request(body(post("/api/v1/auth/refresh"),Map.of("refreshToken",refresh)),null,401);
        request(body(post("/api/v1/auth/refresh"),Map.of("refreshToken",next.path("refreshToken").asText())),null,401);
        var other=login();
        request(body(post("/api/v1/auth/logout"),Map.of("refreshToken",other.path("refreshToken").asText())),other,204);
        request(body(post("/api/v1/auth/refresh"),Map.of("refreshToken",other.path("refreshToken").asText())),null,401);
        request(get("/api/v1/me"),other,200); // Access remains valid until expiry by contract.
        request(delete("/api/v1/me"),account,204);
        for(String path:List.of("/api/v1/me","/api/v1/vehicle","/api/v1/parking-events/"+event)) request(get(path),account,401);
        UUID user=UUID.fromString(account.at("/user/id").asText());
        for(String table:List.of("users","vehicles","parking_events","refresh_tokens","user_agreements","location_access_logs","notification_settings")) {
            String key=table.equals("users")?"id":"user_id";
            assertEquals(0,api.queryForObject("SELECT count(*) FROM "+table+" WHERE "+key+"=?",Integer.class,user),"Account child table "+table);
        }
        assertEquals(0,api.queryForObject("SELECT count(*) FROM risk_assessments WHERE parking_event_id=?",Integer.class,UUID.fromString(event)));
    }
    <T> List<T> concurrent(Callable<T> operation) throws Exception {
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<T> work=()->{ if(!start.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Concurrency start timeout"); return operation.call(); };
            var first=pool.submit(work); var second=pool.submit(work); start.countDown();
            return List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
        }
    }
}
