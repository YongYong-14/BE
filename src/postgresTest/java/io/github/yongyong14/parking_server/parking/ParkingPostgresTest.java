package io.github.yongyong14.parking_server.parking;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import io.github.yongyong14.parking_server.global.exception.*;
import io.github.yongyong14.parking_server.global.security.AgreementPolicy;
import static org.assertj.core.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ParkingPostgresTest {
    JdbcTemplate owner,api;
    DataSource source;
    TransactionTemplate tx;
    ParkingTransactions service;
    ParkingStore store;
    UUID user,vehicle,version,importId;
    final Instant now=Instant.parse("2026-11-07T10:05:41Z");
    final Clock clock=Clock.fixed(now,ZoneOffset.UTC);
    @BeforeAll void initialize() throws Exception {
        String url=required("PARKING_TEST_JDBC_URL");
        if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:\\d+/parking_event_verify")) throw new IllegalStateException("Expected isolated parking DB");
        owner=new JdbcTemplate(dataSource(url,"parking_migrator"));
        assertIdentity(owner,"parking_migrator");
        for (String file:List.of("V1__verify_postgis.sql","V2__erd_v5_schema.sql","V3__erd_v5_guards_and_indexes.sql","V4__erd_v5_grants.sql","V5__parking_lots_hours_and_fee.sql")) {
            String sql=new ClassPathResource("db/migration/"+file).getContentAsString(StandardCharsets.UTF_8);
            var migrationTx=new TransactionTemplate(new DataSourceTransactionManager(owner.getDataSource()));
            migrationTx.executeWithoutResult(s->owner.execute(sql));
        }
        source=dataSource(url,"parking_api");
        api=new JdbcTemplate(source);
        assertIdentity(api,"parking_api");
        tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        owner.execute("""
                INSERT INTO zone_types VALUES ('HYDRANT','소화전',true),('BUS_STOP','버스정류소',true);
                INSERT INTO zone_rule_sets(rule_set) VALUES ('parking-test');
                INSERT INTO zone_rules(id,rule_set,zone_type,geometry_rule,legal_distance_m,fine_amount_car)
                  VALUES (gen_random_uuid(),'parking-test','HYDRANT','DISTANCE',5,80000),
                         (gen_random_uuid(),'parking-test','BUS_STOP','DISTANCE',10,40000);
                UPDATE zone_rule_sets SET published_at=now() WHERE rule_set='parking-test';
                INSERT INTO spatial_grids(id,grid_x,grid_y,boundary)
                  VALUES (101,4170,10500,ST_MakeEnvelope(127,37,128,38,4326)),
                         (102,4171,10501,ST_MakeEnvelope(127,37,128,38,4326)),
                         (103,4172,10501,ST_MakeEnvelope(127,37,128,38,4326));
                """);
    }
    DriverManagerDataSource dataSource(String url,String login) {
        var ds=new DriverManagerDataSource(url,login,required("PARKING_TEST_PASSWORD"));
        var props=new Properties(); props.setProperty("logServerErrorDetail","false"); ds.setConnectionProperties(props); return ds;
    }
    void assertIdentity(JdbcTemplate jdbc,String login) {
        assertThat(jdbc.queryForObject("SELECT current_database()||':'||current_user||':'||current_setting('verification.identity',true)",String.class))
                .isEqualTo("parking_event_verify:"+login+":"+required("PARKING_TEST_IDENTITY"));
    }
    @BeforeEach void setup() {
        owner.execute("UPDATE prediction_versions SET active=false; UPDATE data_imports SET active=false; DELETE FROM facilities; DELETE FROM users;");
        user=UUID.randomUUID(); vehicle=UUID.randomUUID(); importId=UUID.randomUUID(); version=UUID.randomUUID();
        owner.update("INSERT INTO users(id,kakao_id) VALUES (?,?)",user,ThreadLocalRandom.current().nextLong());
        owner.update("INSERT INTO user_agreements(user_id,agreement_type,version,action) VALUES (?,'LOCATION_SERVICE','parking-test-version','AGREE')",user);
        owner.update("INSERT INTO vehicles(id,user_id,name,bluetooth_address) VALUES (?,?,'snapshot-name','AA:BB:CC:DD:EE:FF')",vehicle,user);
        owner.update("INSERT INTO notification_settings(user_id) VALUES (?)",user);
        owner.update("INSERT INTO data_imports(id,source_dataset,file_name,file_hash,active) VALUES (?,'yongin_enforcement','isolated-fixture',?,true)",importId,UUID.randomUUID().toString());
        owner.update("""
                INSERT INTO prediction_versions(id,model_type,model_version,training_start,training_end,target_definition,source_import_id,observed_end,
                  smoothing_radius_m,percentile_basis,caution_top_percent,target_cell_count)
                VALUES (?,'BASELINE_MEAN',?,'2025-01-01','2025-09-25','ROW_COUNT',?,'2025-09-25',50,'SAME_SLOT',10,2)
                """,version,"isolated-"+version,importId);
        owner.update("INSERT INTO prediction_target_grids VALUES (?,101),(?,102)",version,version);
        owner.update("""
                INSERT INTO risk_predictions(version_id,grid_id,weekday,time_slot,expected_count,smoothed_count,top_percent)
                SELECT ?,g,w,s,0,0,CASE WHEN g=101 THEN 1.23456789 ELSE 70 END
                FROM generate_series(101,102) g CROSS JOIN generate_series(1,7) w CROSS JOIN generate_series(0,7) s
                """,version);
        owner.queryForObject("SELECT freeze_prediction_version(?)",Object.class,version);
        owner.queryForObject("SELECT activate_prediction_version(?)",Object.class,version);
        store=new ParkingStore(source);
        var settings=new ParkingSettings("parking-test",List.of("HYDRANT","BUS_STOP"),Duration.ofMinutes(2),Duration.ofMinutes(10),10,50);
        service=new ParkingTransactions(store,new ParkingAssessmentEngine(store,settings,new PostgisGridResolver()),settings,
                new AgreementPolicy(Set.of("parking-test-version")),clock);
    }
    ParkingRequest request(UUID key,String lat,String lng) {
        var t=OffsetDateTime.parse("2026-11-07T19:05:12+09:00");
        return new ParkingRequest(key,vehicle,t,t.plusSeconds(28),ParkingRequest.Method.CONFIRMED,null,null,
                new ParkingRequest.Point(new BigDecimal(lat),new BigDecimal(lng)),0);
    }
    ParkingRequest sample(UUID key) { return request(key,"37.324450","127.096184"); }
    ParkingTransactions.Result save(ParkingRequest r) { return tx.execute(s->service.save(user,r)); }
    @Test void suppliedGridSamplesMatchActualPostgisTransform() {
        for (var row:List.of(List.of("37.324450","127.096184","4170","10500"),List.of("37.324900","127.096749","4171","10501"))) {
            var actual=api.queryForMap("SELECT floor(ST_X(p)/50)::int x,floor(ST_Y(p)/50)::int y FROM (SELECT ST_Transform(ST_SetSRID(ST_MakePoint(?,?),4326),5186) p) t",
                    Double.parseDouble(row.get(1)),Double.parseDouble(row.get(0)));
            assertThat(actual.get("x")).isEqualTo(Integer.valueOf(row.get(2)));
            assertThat(actual.get("y")).isEqualTo(Integer.valueOf(row.get(3)));
        }
        assertThat(new PostgisGridResolver().resolve(store.jdbc,new BigDecimal("37.324450"),new BigDecimal("127.096184"),5186,50).id()).isEqualTo(101);
        var boundary=api.queryForMap("SELECT floor(-0.0001/50.0)::int negative,floor(50.0/50.0)::int boundary");
        assertThat(boundary.get("negative")).isEqualTo(-1); assertThat(boundary.get("boundary")).isEqualTo(1);
    }
    @Test void newModelResultReplayAndReplacedVehicle() {
        var r=sample(UUID.randomUUID()); var first=save(r);
        assertThat(first.created()).isTrue();
        assertThat(first.assessment()).containsEntry("status","SUCCEEDED").containsEntry("level",2)
                .containsEntry("weekday_actual",6).containsEntry("weekday",6).containsEntry("time_slot",6)
                .containsEntry("top_percent",1.23456789).containsEntry("predicted_count",0.0);
        assertThat(first.reasons()).hasSize(1);
        owner.update("UPDATE vehicles SET name='changed',deleted_at=now() WHERE id=?",vehicle);
        var replay=save(r);
        assertThat(replay.created()).isFalse(); assertThat(replay.assessment()).isEqualTo(first.assessment());
        assertThat(api.queryForObject("SELECT vehicle_name_snapshot FROM parking_events WHERE id=?",String.class,first.parkingEventId())).isEqualTo("snapshot-name");
        assertThat(api.queryForObject("SELECT count(*) FROM location_access_logs WHERE parking_event_id=?",Integer.class,first.parkingEventId())).isEqualTo(1);
        assertCode(()->save(request(r.clientEventId(),"37.324451","127.096184")),ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }
    @Test void simultaneousNewRequestsProduceOneEvent() throws Exception {
        var r=sample(UUID.randomUUID());
        var results=race(r);
        assertThat(results.stream().filter(ParkingTransactions.Result::created).count()).isEqualTo(1);
        assertThat(results.get(0).assessment()).isEqualTo(results.get(1).assessment());
    }
    List<ParkingTransactions.Result> race(ParkingRequest request) throws Exception {
        var gate=new CyclicBarrier(2);
        try (var pool=Executors.newFixedThreadPool(2)) {
            Callable<ParkingTransactions.Result> task=()-> { gate.await(5,TimeUnit.SECONDS); return save(request); };
            var a=pool.submit(task); var b=pool.submit(task);
            return List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        }
    }
    @Test void failureRetryIsSerializedAndRollsBackAttemptOnError() throws Exception {
        // Produce an expected data-contract failure without corrupting frozen data.
        owner.update("UPDATE data_imports SET active=false WHERE id=?",importId);
        var r=sample(UUID.randomUUID()); var first=save(r);
        assertThat(first.assessment()).containsEntry("status","FAILED").containsEntry("error_code","SOURCE_IMPORT_MISMATCH");
        assertThatThrownBy(()->tx.execute(s->{ service.save(user,r); throw new IllegalStateException("test rollback"); })).isInstanceOf(IllegalStateException.class);
        assertThat(store.assessment(first.parkingEventId()).get("attempt_count")).isEqualTo(1);
        var results=race(r);
        assertThat(results).allSatisfy(x->assertThat(x.assessment().get("attempt_count")).isEqualTo(2));
        assertThat(api.queryForObject("SELECT count(*) FROM location_access_logs WHERE parking_event_id=?",Integer.class,first.parkingEventId())).isEqualTo(2);
    }
    @Test void partialCasesAndLevelThreeRemainDistinct() {
        var three=save(request(UUID.randomUUID(),"37.324900","127.096749"));
        assertThat(three.assessment()).containsEntry("status","SUCCEEDED").containsEntry("level",3);
        var outside=save(request(UUID.randomUUID(),"37","128"));
        assertThat(outside.assessment()).containsEntry("status","PARTIAL").containsEntry("prediction_status","OUT_OF_AREA").containsEntry("level",null);
        var targetPoint=api.queryForMap("SELECT ST_Y(p) lat,ST_X(p) lng FROM (SELECT ST_Transform(ST_SetSRID(ST_MakePoint(4172*50+25,10501*50+25),5186),4326) p) t");
        var notTarget=save(request(UUID.randomUUID(),targetPoint.get("lat").toString(),targetPoint.get("lng").toString()));
        assertThat(notTarget.assessment()).containsEntry("status","PARTIAL").containsEntry("prediction_status","NOT_TARGET");
        owner.execute("UPDATE prediction_versions SET active=false");
        var noVersion=save(sample(UUID.randomUUID()));
        assertThat(noVersion.assessment()).containsEntry("status","PARTIAL").containsEntry("prediction_status","NO_ACTIVE_VERSION");
    }
    @Test void facilityWinsAndAccuracyIsRecordedWithoutNewGradePolicy() {
        owner.update("INSERT INTO facilities(id,import_id,source_record_key,zone_type,spatial_data) VALUES (?,?,?,'HYDRANT',ST_SetSRID(ST_MakePoint(127.096184,37.324450),4326)::geography)",UUID.randomUUID(),importId,"hydrant");
        var base=sample(UUID.randomUUID());
        var r=new ParkingRequest(base.clientEventId(),vehicle,base.detectedAt(),base.confirmedAt(),ParkingRequest.Method.CONFIRMED,
                new ParkingRequest.Gps(base.confirmedLocation().lat(),base.confirmedLocation().lng(),new BigDecimal("75")),null,base.confirmedLocation(),0);
        var result=save(r);
        assertThat(result.assessment()).containsEntry("level",1).containsEntry("prediction_status","SKIPPED").containsEntry("reference_fine_amount",80000);
        assertThat(result.reasons().getFirst()).containsEntry("accuracy_margin_m",75.0).containsEntry("reason_type","FACILITY_RULE");
    }
    @Test void latestAgreementIdOverridesTimestampAndReplaysNeedConsent() {
        var r=sample(UUID.randomUUID()); save(r);
        owner.update("INSERT INTO user_agreements(user_id,agreement_type,version,action,occurred_at) VALUES (?,'LOCATION_SERVICE','parking-test-version','WITHDRAW','2020-01-01')",user);
        assertCode(()->save(r),ErrorCode.LOCATION_CONSENT_REQUIRED);
    }
    @Test void ownershipAndDistanceValidationDoNotInsertAnything() {
        var base=sample(UUID.randomUUID());
        var foreign=new ParkingRequest(base.clientEventId(),UUID.randomUUID(),base.detectedAt(),base.confirmedAt(),base.confirmationMethod(),null,null,base.confirmedLocation(),0);
        assertCode(()->save(foreign),ErrorCode.VEHICLE_NOT_FOUND);
        var far=new ParkingRequest(base.clientEventId(),vehicle,base.detectedAt(),base.confirmedAt(),base.confirmationMethod(),new ParkingRequest.Gps(BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ONE),null,base.confirmedLocation(),0);
        assertCode(()->save(far),ErrorCode.ADJUST_DISTANCE_EXCEEDED);
        assertThat(api.queryForObject("SELECT count(*) FROM parking_events",Integer.class)).isZero();
    }
    @Test void realRuntimePermissionsAndDeferredReasonGuard() {
        assertThatThrownBy(()->api.execute("UPDATE prediction_versions SET active=false")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var result=save(sample(UUID.randomUUID()));
        assertThatThrownBy(()->tx.executeWithoutResult(s->api.update("DELETE FROM assessment_reasons WHERE assessment_id=?",result.assessment().get("id"))))
                .isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
        assertThat(store.reasons((UUID)result.assessment().get("id"))).hasSize(1);
    }
    @Test void missingPredictionFaultIsFailedAndOneRetryRecovers() {
        var settings=new ParkingSettings("parking-test",List.of("HYDRANT","BUS_STOP"),Duration.ofMinutes(2),Duration.ofMinutes(10),10,50);
        var fault=new ParkingAssessmentEngine(store,settings,new PostgisGridResolver()) {
            @Override protected List<Map<String,Object>> predictions(Map<String,Object> key) { return List.of(); }
        };
        var failing=new ParkingTransactions(store,fault,settings,new AgreementPolicy(Set.of("parking-test-version")),clock);
        var request=sample(UUID.randomUUID());
        var first=tx.execute(s->failing.save(user,request));
        assertThat(first.assessment()).containsEntry("status","FAILED").containsEntry("prediction_status","MISSING_ROW").containsEntry("level",null);
        var recovered=save(request);
        assertThat(recovered.assessment()).containsEntry("status","SUCCEEDED").containsEntry("attempt_count",2).containsEntry("level",2);
        assertThat(recovered.reasons()).hasSize(1);
        assertThat(save(request).assessment()).isEqualTo(recovered.assessment());
    }
    @Test void holidayPolicyAndKstTimeSlotAreAppliedWithoutReplacingActualWeekday() {
        owner.update("INSERT INTO holidays VALUES ('2026-11-07','isolated test holiday')");
        try {
            var assessment=save(sample(UUID.randomUUID())).assessment();
            assertThat(assessment).containsEntry("weekday_actual",6).containsEntry("weekday",7)
                    .containsEntry("time_slot",6).containsEntry("is_holiday",true);
        } finally { owner.update("DELETE FROM holidays WHERE holiday_date='2026-11-07'"); }
        var base=sample(UUID.randomUUID());
        var boundary=OffsetDateTime.parse("2026-11-08T03:00:00+09:00");
        var boundaryService=new ParkingTransactions(store,new ParkingAssessmentEngine(store,
                new ParkingSettings("parking-test",List.of(),Duration.ofMinutes(2),Duration.ofMinutes(10),10,50),new PostgisGridResolver()),
                new ParkingSettings("parking-test",List.of(),Duration.ofMinutes(2),Duration.ofMinutes(10),10,50),
                new AgreementPolicy(Set.of("parking-test-version")),Clock.fixed(boundary.toInstant(),ZoneOffset.UTC));
        var r=new ParkingRequest(base.clientEventId(),vehicle,boundary,boundary,base.confirmationMethod(),null,null,base.confirmedLocation(),0);
        var a=tx.execute(s->boundaryService.save(user,r)).assessment();
        assertThat(a).containsEntry("weekday_actual",7).containsEntry("time_slot",1);
    }
    @Test void withdrawalUserLockPreventsNewLocationAfterCommit() throws Exception {
        var request=sample(UUID.randomUUID());
        try (var connection=owner.getDataSource().getConnection(); var pool=Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var lock=connection.prepareStatement("SELECT id FROM users WHERE id=? FOR UPDATE")) {
                lock.setObject(1,user); lock.executeQuery().close();
            }
            var started=new CountDownLatch(1);
            var future=pool.submit(()->{started.countDown(); return save(request);});
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->future.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            try (var withdraw=connection.prepareStatement("INSERT INTO user_agreements(user_id,agreement_type,version,action) VALUES (?,'LOCATION_SERVICE','parking-test-version','WITHDRAW')")) {
                withdraw.setObject(1,user); withdraw.executeUpdate();
            }
            connection.commit();
            assertThatThrownBy(()->future.get(5,TimeUnit.SECONDS)).hasCauseInstanceOf(BusinessException.class);
            assertThat(api.queryForObject("SELECT count(*) FROM parking_events",Integer.class)).isZero();
        }
    }
    @Test void parkingVehicleShareLockBlocksReplacementUntilCommit() throws Exception {
        var request=sample(UUID.randomUUID());
        try (var pool=Executors.newSingleThreadExecutor()) {
            var pending=new java.util.concurrent.atomic.AtomicReference<Future<Integer>>();
            tx.executeWithoutResult(s->{
                service.save(user,request);
                var future=pool.submit(()->owner.update("UPDATE vehicles SET deleted_at=now() WHERE id=?",vehicle));
                pending.set(future);
                assertThatThrownBy(()->future.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            });
            assertThat(pending.get().get(5,TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(save(request).created()).isFalse();
        }
    }
    @Test void exactFutureToleranceAndReplayIgnoreLaterClockChanges() {
        var time=now.plusSeconds(120).atOffset(ZoneOffset.UTC);
        var base=sample(UUID.randomUUID());
        var boundary=new ParkingRequest(base.clientEventId(),vehicle,time,time,base.confirmationMethod(),null,null,base.confirmedLocation(),0);
        var first=save(boundary);
        var settings=new ParkingSettings("parking-test",List.of(),Duration.ofMinutes(2),Duration.ofMinutes(10),10,50);
        var earlierClock=new ParkingTransactions(store,new ParkingAssessmentEngine(store,settings,new PostgisGridResolver()),settings,
                new AgreementPolicy(Set.of("parking-test-version")),Clock.fixed(now.minusSeconds(3600),ZoneOffset.UTC));
        assertThat(tx.execute(s->earlierClock.save(user,boundary)).assessment()).isEqualTo(first.assessment());
        var withinMicrosecond=new ParkingRequest(UUID.randomUUID(),vehicle,time.plusNanos(999),time.plusNanos(999),base.confirmationMethod(),null,null,base.confirmedLocation(),0);
        save(withinMicrosecond);
        var invalid=new ParkingRequest(UUID.randomUUID(),vehicle,time.plusNanos(1000),time.plusNanos(1000),base.confirmationMethod(),null,null,base.confirmedLocation(),0);
        assertCode(()->save(invalid),ErrorCode.INVALID_TIME);
    }
    @Test void actualFacilityDistanceAndConfiguredRepresentativeRankingAreDeterministic() {
        UUID hydrant=new UUID(0,42),bus=new UUID(0,43);
        owner.update("""
                INSERT INTO facilities(id,import_id,source_record_key,zone_type,spatial_data)
                VALUES (?,?,?,'HYDRANT',ST_Project(ST_SetSRID(ST_MakePoint(127.096184,37.324450),4326)::geography,4.9,0)),
                       (?,?,?,'BUS_STOP',ST_Project(ST_SetSRID(ST_MakePoint(127.096184,37.324450),4326)::geography,9.9,0))
                """,hydrant,importId,"hydrant-distance",bus,importId,"bus-distance");
        var r=sample(UUID.randomUUID()); var a=save(r);
        assertThat(a.assessment()).containsEntry("level",1).containsEntry("primary_zone_type","HYDRANT");
        assertThat(a.reasons()).hasSize(2);
        assertThat(a.reasons().getFirst()).containsEntry("facility_id",hydrant).containsEntry("threshold_m",5.0);
        assertThat(((Number)a.reasons().getFirst().get("distance_m")).doubleValue()).isCloseTo(4.9,within(0.001));
        var settings=new ParkingSettings("parking-test",List.of(),Duration.ofMinutes(2),Duration.ofMinutes(10),10,50);
        var noPriority=new ParkingTransactions(store,new ParkingAssessmentEngine(store,settings,new PostgisGridResolver()),settings,
                new AgreementPolicy(Set.of("parking-test-version")),clock);
        var pending=tx.execute(s->noPriority.save(user,sample(UUID.randomUUID())));
        assertThat(pending.assessment()).containsEntry("status","FAILED").containsEntry("level",null).containsEntry("error_code","FACILITY_PRIORITY_UNCONFIGURED");
    }
    @org.springframework.context.annotation.Configuration
    @org.springframework.transaction.annotation.EnableTransactionManagement
    static class TransactionWiring {}
    org.springframework.context.annotation.AnnotationConfigApplicationContext context(ReverseGeocoder geocoder,Queue<Runnable> tasks) {
        var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        context.register(TransactionWiring.class);
        context.registerBean(DataSource.class,()->source);
        context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(Clock.class,()->clock);
        context.registerBean(ParkingSettings.class,()->new ParkingSettings("parking-test",List.of("HYDRANT","BUS_STOP"),Duration.ofMinutes(2),Duration.ofMinutes(10),10,50));
        context.registerBean(AgreementPolicy.class,()->new AgreementPolicy(Set.of("parking-test-version")));
        context.registerBean(ParkingNotificationPolicy.class,()->new ParkingNotificationPolicy(2));
        context.registerBean(GridResolver.class,PostgisGridResolver::new);
        context.registerBean(ReverseGeocoder.class,()->geocoder);
        context.registerBean("parkingGeocodingExecutor",java.util.concurrent.Executor.class,()->tasks::add);
        context.register(ParkingStore.class,ParkingAssessmentEngine.class,ParkingTransactions.class,ParkingService.class,
                ParkingGeocodingTransactions.class,ParkingGeocodingListener.class);
        context.refresh();
        return context;
    }
    static class FakeGeocoder implements ReverseGeocoder {
        int calls;
        boolean enabled=true,fail;
        @Override public boolean enabled() { return enabled; }
        @Override public Optional<String> address(BigDecimal lat,BigDecimal lng) throws Exception {
            calls++; if (fail) throw new java.io.IOException("test external failure"); return Optional.of("격리 테스트 주소");
        }
    }
    @Test void httpThroughActualAuthenticationAndDatabaseHas201ThenImmutable200() throws Exception {
        var tasks=new ArrayDeque<Runnable>(); var fake=new FakeGeocoder();
        try (var context=context(fake,tasks)) {
            var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
            var secret=new byte[32]; new java.security.SecureRandom().nextBytes(secret);
            var tokens=new io.github.yongyong14.parking_server.global.security.AccessTokenService(
                    new io.github.yongyong14.parking_server.global.security.TokenSettings("isolated-parking-http",Base64.getEncoder().encodeToString(secret),Duration.ofMinutes(30),Duration.ofDays(30)),clock);
            Arrays.fill(secret,(byte)0);
            var filter=new io.github.yongyong14.parking_server.global.security.AccessTokenFilter(tokens,
                    new io.github.yongyong14.parking_server.global.security.JdbcUserExistence(org.springframework.jdbc.core.simple.JdbcClient.create(source)),
                    new io.github.yongyong14.parking_server.global.security.SecurityErrorWriter(mapper),request->false);
            var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new ParkingController(context.getBean(ParkingService.class)))
                    .setControllerAdvice(new GlobalExceptionHandler())
                    .setCustomArgumentResolvers(new io.github.yongyong14.parking_server.global.security.CurrentUserResolver())
                    .addFilters(filter).build();
            String bearer="Bearer "+tokens.issue(user).accessToken();
            var request=sample(UUID.randomUUID()); String json=mapper.writeValueAsString(request);
            var post=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/parking-events");
            String first=mvc.perform(post.header("Authorization",bearer).contentType("application/json").content(json))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            assertThat(mapper.readTree(first).at("/data/assessment/notify/decision").asText()).isEqualTo("SEND");
            assertThat(tasks).hasSize(1); assertThat(fake.calls).isZero();
            owner.update("UPDATE notification_settings SET caution_enabled=false,night_enabled=false WHERE user_id=?",user);
            owner.update("UPDATE vehicles SET deleted_at=now() WHERE id=?",vehicle);
            var replay=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/parking-events")
                    .header("Authorization",bearer).contentType("application/json").content(json))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(mapper.readTree(replay)).isEqualTo(mapper.readTree(first));
            tasks.remove().run();
            assertThat(fake.calls).isEqualTo(1);
            assertThat(api.queryForObject("SELECT address FROM parking_events WHERE client_event_id=?",String.class,request.clientEventId())).isEqualTo("격리 테스트 주소");
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/parking-events")
                    .header("Authorization",bearer).contentType("application/json").content(json.replace("\"remeasureCount\":0","\"remeasureCount\":1")))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/parking-events")
                    .header("Authorization",bearer).contentType("application/json").content(mapper.writeValueAsString(sample(UUID.randomUUID()))))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.code").value("VEHICLE_NOT_FOUND"));
            owner.update("INSERT INTO user_agreements(user_id,agreement_type,version,action) VALUES (?,'LOCATION_SERVICE','parking-test-version','WITHDRAW')",user);
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/parking-events")
                    .header("Authorization",bearer).contentType("application/json").content(json))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.code").value("LOCATION_CONSENT_REQUIRED"));
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }
    @Test void addressTaskIsRegisteredOnlyAfterCommitAndNeverOnRollbackOrReplay() {
        var tasks=new ArrayDeque<Runnable>(); var fake=new FakeGeocoder();
        try (var context=context(fake,tasks)) {
            var transactions=context.getBean(ParkingTransactions.class);
            var rolledBack=sample(UUID.randomUUID());
            tx.executeWithoutResult(s->{
                transactions.save(user,rolledBack);
                assertThat(tasks).isEmpty(); s.setRollbackOnly();
            });
            assertThat(tasks).isEmpty();
            assertThat(store.existing(user,rolledBack.clientEventId())).isNull();
            var request=sample(UUID.randomUUID());
            var saved=transactions.save(user,request);
            assertThat(tasks).hasSize(1);
            assertThat(fake.calls).isZero();
            tasks.remove().run();
            assertThat(fake.calls).isEqualTo(1);
            assertThat(store.assessment(saved.parkingEventId())).isEqualTo(saved.assessment());
            transactions.save(user,request);
            assertThat(tasks).isEmpty();
            assertThat(api.queryForObject("SELECT count(*) FROM location_access_logs WHERE parking_event_id=? AND action='PROVIDE'",Integer.class,saved.parkingEventId())).isEqualTo(1);
        }
    }
    @Test void externalFailureOrMissingKeyCannotUndoParkingAndAssessment() {
        var tasks=new ArrayDeque<Runnable>(); var fake=new FakeGeocoder(); fake.fail=true;
        try (var context=context(fake,tasks)) {
            var transactions=context.getBean(ParkingTransactions.class);
            var saved=transactions.save(user,sample(UUID.randomUUID()));
            tasks.remove().run();
            assertThat(store.event(user,saved.parkingEventId()).get("address")).isNull();
            assertThat(store.assessment(saved.parkingEventId())).isEqualTo(saved.assessment());
            assertThat(fake.calls).isEqualTo(1);
            fake.enabled=false;
            var noKey=transactions.save(user,sample(UUID.randomUUID()));
            tasks.remove().run();
            assertThat(fake.calls).isEqualTo(1);
            assertThat(store.event(user,noKey.parkingEventId()).get("address")).isNull();
        }
    }
    @Test void deletedEventAndWithdrawnConsentPreventExternalTransmissionAndRecreation() {
        var tasks=new ArrayDeque<Runnable>(); var fake=new FakeGeocoder();
        try (var context=context(fake,tasks)) {
            var transactions=context.getBean(ParkingTransactions.class);
            var deleted=transactions.save(user,sample(UUID.randomUUID()));
            owner.update("DELETE FROM parking_events WHERE id=?",deleted.parkingEventId());
            tasks.remove().run();
            assertThat(fake.calls).isZero();
            var withdrawn=transactions.save(user,sample(UUID.randomUUID()));
            owner.update("INSERT INTO user_agreements(user_id,agreement_type,version,action) VALUES (?,'LOCATION_SERVICE','parking-test-version','WITHDRAW')",user);
            tasks.remove().run();
            assertThat(fake.calls).isZero();
            assertThat(store.event(user,withdrawn.parkingEventId()).get("address")).isNull();
            assertThat(api.queryForObject("SELECT count(*) FROM parking_events WHERE id=?",Integer.class,deleted.parkingEventId())).isZero();
        }
    }
    @Test void geocodingHoldsConsentLockAcrossExternalCallSoDeletionWinsAfterward() throws Exception {
        var tasks=new ArrayDeque<Runnable>();
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var fake=new FakeGeocoder() {
            @Override public Optional<String> address(BigDecimal lat,BigDecimal lng) throws Exception {
                entered.countDown(); if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                return super.address(lat,lng);
            }
        };
        try (var context=context(fake,tasks); var pool=Executors.newFixedThreadPool(2)) {
            var saved=context.getBean(ParkingTransactions.class).save(user,sample(UUID.randomUUID()));
            var geocoding=pool.submit(tasks.remove());
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            var deletion=pool.submit(()->{
                var deletionTx=new TransactionTemplate(new DataSourceTransactionManager(owner.getDataSource()));
                deletionTx.executeWithoutResult(s->{
                    owner.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",user);
                    owner.update("DELETE FROM users WHERE id=?",user);
                });
            });
            try { assertThatThrownBy(()->deletion.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { release.countDown(); }
            geocoding.get(5,TimeUnit.SECONDS); deletion.get(5,TimeUnit.SECONDS);
            assertThat(api.queryForObject("SELECT count(*) FROM parking_events WHERE id=?",Integer.class,saved.parkingEventId())).isZero();
        } finally { release.countDown(); }
    }
    @Test void suppressedDecisionAndExpirySurvivePreferenceModelAndClockChanges() {
        var settings=new ParkingSettings("parking-test",List.of("HYDRANT","BUS_STOP"),Duration.ofMinutes(2),Duration.ofMinutes(10),10,50);
        var policy=new ParkingNotificationPolicy(0.5); // fixture is 1.23456789: ordinary level 2
        var custom=new ParkingTransactions(store,new ParkingAssessmentEngine(store,settings,new PostgisGridResolver(),policy),settings,
                new AgreementPolicy(Set.of("parking-test-version")),clock);
        owner.update("UPDATE notification_settings SET caution_enabled=false,night_enabled=false WHERE user_id=?",user);
        var r=sample(UUID.randomUUID());
        var first=tx.execute(s->custom.save(user,r));
        var firstResponse=ParkingResponse.from(first);
        assertThat(firstResponse.assessment().notification().decision()).isEqualTo("SUPPRESSED_SETTING");
        owner.update("UPDATE notification_settings SET caution_enabled=true,night_enabled=true WHERE user_id=?",user);
        owner.execute("UPDATE prediction_versions SET active=false");
        assertThat(ParkingResponse.from(save(r))).isEqualTo(firstResponse);
        var later=new ParkingTransactions(store,new ParkingAssessmentEngine(store,settings,new PostgisGridResolver()),settings,
                new AgreementPolicy(Set.of("parking-test-version")),Clock.fixed(now.plusSeconds(86400),ZoneOffset.UTC));
        assertThat(ParkingResponse.from(tx.execute(s->later.save(user,r)))).isEqualTo(firstResponse);
    }
    @Test void pausedNewRequestRechecksWinnerAfterVehicleReplacementAndReturnsStoredResult() throws Exception {
        replayAfterReplacement(false);
    }
    @Test void pausedDifferentBodyRechecksWinnerAfterVehicleReplacementAndReturnsConflict() throws Exception {
        replayAfterReplacement(true);
    }
    void replayAfterReplacement(boolean changedBody) throws Exception {
        var absentSeen=new CountDownLatch(1); var resume=new CountDownLatch(1);
        var firstLookup=new java.util.concurrent.atomic.AtomicBoolean(true);
        var eventLocks=new java.util.concurrent.atomic.AtomicInteger();
        var pausedStore=new ParkingStore(source) {
            @Override UUID existing(UUID u,UUID key) {
                var found=super.existing(u,key);
                if (found==null && firstLookup.compareAndSet(true,false)) {
                    absentSeen.countDown();
                    try { if(!resume.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Paused request timed out"); }
                    catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                }
                return found;
            }
            @Override Map<String,Object> locked(UUID u,UUID key) {
                eventLocks.incrementAndGet(); return super.locked(u,key);
            }
        };
        var settings=new ParkingSettings("parking-test",List.of("HYDRANT","BUS_STOP"),Duration.ofMinutes(2),Duration.ofMinutes(10),10,50);
        var bService=new ParkingTransactions(pausedStore,new ParkingAssessmentEngine(pausedStore,settings,new PostgisGridResolver()),settings,
                new AgreementPolicy(Set.of("parking-test-version")),clock);
        var request=sample(UUID.randomUUID());
        var second=new ParkingRequest(request.clientEventId(),vehicle,request.detectedAt(),request.confirmedAt(),request.confirmationMethod(),
                null,null,request.confirmedLocation(),changedBody ? 1 : 0);
        try (var pool=Executors.newSingleThreadExecutor()) {
            var b=pool.submit(()->tx.execute(s->bService.save(user,second)));
            try {
                assertThat(absentSeen.await(5,TimeUnit.SECONDS)).isTrue();
                var a=save(request); // transaction committed before replacement begins
                assertThat(a.created()).isTrue();
                // Same user -> vehicle order as VehicleTransactions; existing event remains attached to the retired row.
                tx.executeWithoutResult(s->{
                    api.queryForList("SELECT id FROM users WHERE id=? FOR SHARE",user);
                    api.update("UPDATE vehicles SET deleted_at=now() WHERE id=?",vehicle);
                    api.update("INSERT INTO vehicles(id,user_id,name,bluetooth_address) VALUES (?,?,'replacement','00:11:22:33:44:55')",UUID.randomUUID(),user);
                });
                resume.countDown();
                if (changedBody) {
                    assertThatThrownBy(()->b.get(5,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                            .satisfies(failure->assertThat(((BusinessException)failure.getCause()).getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
                } else {
                    var result=b.get(5,TimeUnit.SECONDS);
                    assertThat(result.created()).isFalse();
                    assertThat(result.parkingEventId()).isEqualTo(a.parkingEventId());
                    assertThat(result.assessment()).isEqualTo(a.assessment());
                }
                assertThat(eventLocks.get()).isEqualTo(1);
                assertThat(api.queryForObject("SELECT count(*) FROM parking_events WHERE user_id=? AND client_event_id=?",Integer.class,user,request.clientEventId())).isEqualTo(1);
                assertThat(store.assessment(a.parkingEventId()).get("attempt_count")).isEqualTo(1);
            } finally { resume.countDown(); }
        }
    }
    @Test void missingEventStillRequiresOwnedActiveVehicleAfterFallbackLookup() {
        owner.update("UPDATE vehicles SET deleted_at=now() WHERE id=?",vehicle);
        assertCode(()->save(sample(UUID.randomUUID())),ErrorCode.VEHICLE_NOT_FOUND);
        assertThat(api.queryForObject("SELECT count(*) FROM parking_events",Integer.class)).isZero();
    }
    @Test void microsecondStoredTimesReproduceSlotsWeekdaysNightAndExpiry() {
        var settings=new ParkingSettings("parking-test",List.of("HYDRANT","BUS_STOP"),Duration.ofMinutes(2),Duration.ofMinutes(10),10,50);
        var policy=new ParkingNotificationPolicy(0.5); // force ordinary level 2 to exercise night settings
        for (String timestamp:List.of("2026-11-08T02:59:59.999999999+09:00","2026-11-08T03:00:00+09:00",
                "2026-11-07T23:59:59.999999999+09:00","2026-11-08T00:00:00+09:00",
                "2026-11-07T21:59:59.999999999+09:00","2026-11-07T22:00:00+09:00",
                "2026-11-08T06:59:59.999999999+09:00","2026-11-08T07:00:00+09:00")) {
            var raw=OffsetDateTime.parse(timestamp); var canonical=raw.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            var clock=Clock.fixed(raw.toInstant(),ZoneOffset.UTC);
            var useCase=new ParkingTransactions(store,new ParkingAssessmentEngine(store,settings,new PostgisGridResolver(),policy),settings,
                    new AgreementPolicy(Set.of("parking-test-version")),clock);
            var key=UUID.randomUUID();
            var request=new ParkingRequest(key,vehicle,raw,raw,ParkingRequest.Method.CONFIRMED,null,
                    new ParkingRequest.LastKnown(new BigDecimal("37.324450"),new BigDecimal("127.096184"),raw.minusMinutes(1)),
                    new ParkingRequest.Point(new BigDecimal("37.324450"),new BigDecimal("127.096184")),0);
            var saved=tx.execute(s->useCase.save(user,request));
            var row=api.queryForMap("SELECT detected_at,confirmed_at,last_known_at FROM parking_events WHERE id=?",saved.parkingEventId());
            var stored=((java.sql.Timestamp)row.get("detected_at")).toInstant();
            assertThat(stored).isEqualTo(canonical.toInstant());
            assertThat(((java.sql.Timestamp)row.get("confirmed_at")).toInstant()).isEqualTo(canonical.toInstant());
            assertThat(((java.sql.Timestamp)row.get("last_known_at")).toInstant()).isEqualTo(canonical.minusMinutes(1).toInstant());
            var kst=stored.atZone(ZoneId.of("Asia/Seoul"));
            assertThat(saved.assessment()).containsEntry("time_slot",kst.getHour()/3).containsEntry("weekday_actual",kst.getDayOfWeek().getValue());
            var expected=kst.getHour()>=22 || kst.getHour()<7 ? "SUPPRESSED_NIGHT" : "SEND";
            assertThat(saved.assessment().get("notify_decision")).isEqualTo(expected);
            var response=ParkingResponse.from(saved);
            assertThat(response.assessment().notification().expiresAt().toInstant()).isEqualTo(stored.plusSeconds(1800));
            var equivalent=new ParkingRequest(key,vehicle,canonical.withOffsetSameInstant(ZoneOffset.UTC),canonical.withOffsetSameInstant(ZoneOffset.UTC),
                    request.confirmationMethod(),null,new ParkingRequest.LastKnown(request.lastKnownLocation().lat(),request.lastKnownLocation().lng(),
                    canonical.minusMinutes(1).withOffsetSameInstant(ZoneOffset.UTC)),request.confirmedLocation(),0);
            var replay=tx.execute(s->useCase.save(user,equivalent));
            assertThat(replay.created()).isFalse();
            assertThat(ParkingResponse.from(replay)).isEqualTo(response);
        }
    }
    @Test void tinyAccuracyAndCoordinatesPersistWithoutExponentExpansion() {
        var r=sample(UUID.randomUUID());
        var gps=new ParkingRequest.Gps(r.confirmedLocation().lat(),r.confirmedLocation().lng(),new BigDecimal("1e-1000000"));
        var request=new ParkingRequest(r.clientEventId(),vehicle,r.detectedAt(),r.confirmedAt(),r.confirmationMethod(),gps,null,r.confirmedLocation(),0);
        var result=save(request);
        assertThat(api.queryForObject("SELECT accuracy_m FROM parking_events WHERE id=?",Double.class,result.parkingEventId())).isEqualTo(0.0);
        assertThat(save(request).assessment()).isEqualTo(result.assessment());
        var tiny=new BigDecimal("1e-2147483647");
        var coordinates=new ParkingRequest(UUID.randomUUID(),vehicle,r.detectedAt(),r.confirmedAt(),r.confirmationMethod(),null,null,new ParkingRequest.Point(tiny,tiny),0);
        var stored=save(coordinates);
        assertThat(api.queryForObject("SELECT ST_X(confirmed_location::geometry) FROM parking_events WHERE id=?",Double.class,stored.parkingEventId())).isEqualTo(0.0);
    }
    void assertCode(Runnable action,ErrorCode code) { assertThatThrownBy(action::run).isInstanceOf(BusinessException.class).extracting(e->((BusinessException)e).getErrorCode()).isEqualTo(code); }
    static String required(String key) { String v=System.getenv(key); if (v==null || v.isBlank()) throw new IllegalStateException("Missing isolated parking test input: "+key); return v; }
}
