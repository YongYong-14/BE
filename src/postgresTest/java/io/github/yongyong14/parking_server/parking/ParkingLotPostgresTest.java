package io.github.yongyong14.parking_server.parking;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import io.github.yongyong14.parking_server.global.exception.*;
import io.github.yongyong14.parking_server.global.security.AgreementPolicy;
import static org.assertj.core.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ParkingLotPostgresTest {
    JdbcTemplate owner,api;
    ParkingStore store;
    TransactionTemplate tx;
    UUID user,imp;
    static String required(String key) { String v=System.getenv(key); if(v==null || v.isBlank()) throw new IllegalStateException("Missing "+key); return v; }
    DriverManagerDataSource source(String role) {
        var source=new DriverManagerDataSource(required("PARKING_TEST_JDBC_URL"),role,required("PARKING_TEST_PASSWORD"));
        var properties=new Properties(); properties.setProperty("logServerErrorDetail","false"); source.setConnectionProperties(properties);
        return source;
    }
    @BeforeAll void migrate() {
        String url=required("PARKING_TEST_JDBC_URL");
        if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:\\d+/parking_event_verify")) throw new IllegalStateException("Expected isolated DB");
        owner=new JdbcTemplate(source("parking_migrator"));
        assertThat(owner.queryForObject("SELECT current_database()||':'||current_setting('verification.identity',true)",String.class))
                .isEqualTo("parking_event_verify:"+required("PARKING_TEST_IDENTITY"));
        var flyway=org.flywaydb.core.Flyway.configure().dataSource(owner.getDataSource()).locations("classpath:db/migration")
                .defaultSchema("flyway").schemas("flyway").placeholderReplacement(false).cleanDisabled(true).load();
        var result=flyway.migrate();
        assertThat(result.migrationsExecuted).isEqualTo((int)Arrays.stream(flyway.info().applied()).filter(m->m.getVersion()!=null).count());
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        var ds=source("parking_api"); api=new JdbcTemplate(ds); store=new ParkingStore(ds);
        assertThat(api.queryForObject("SELECT current_user",String.class)).isEqualTo("parking_api");
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    @BeforeEach void setup() {
        owner.execute("DELETE FROM parking_lots; DELETE FROM data_imports; DELETE FROM users; DELETE FROM holidays");
        user=UUID.randomUUID(); imp=UUID.randomUUID();
        owner.update("INSERT INTO users(id,kakao_id) VALUES (?,1)",user);
        owner.update("INSERT INTO user_agreements(user_id,agreement_type,version,action) VALUES (?,'LOCATION_SERVICE','lot-test','AGREE')",user);
        owner.update("INSERT INTO data_imports(id,source_dataset,file_name,file_hash,active) VALUES (?,'yongin_parking_lots','fixture',?,true)",imp,imp.toString());
    }
    ParkingLotService service(String instant) { return new ParkingLotService(store,new AgreementPolicy("lot-test"),Clock.fixed(Instant.parse(instant),ZoneOffset.UTC)); }
    ParkingLotResponse nearby(int radius) { return tx.execute(s->service("2026-11-06T03:00:00Z").nearby(user,37.32,127.09,radius)); }
    UUID lot(double meters,String name) {
        UUID id=UUID.randomUUID();
        owner.update("""
            INSERT INTO parking_lots(id,import_id,source_record_key,name,location,parking_type,fee_type,capacity,
              weekday_open,weekday_close,saturday_open,saturday_close,holiday_open,holiday_close)
            VALUES (?,?,?, ?,ST_Project(ST_SetSRID(ST_MakePoint(127.09,37.32),4326)::geography,?,0),
              'PUBLIC','FREE',20,'09:00','18:00','10:00','19:00','11:00','20:00')
            """,id,imp,id.toString(),name,meters);
        return id;
    }
    long logs() { return api.queryForObject("SELECT count(*) FROM location_access_logs",Long.class); }
    ParkingLotService transactionalService(String instant) {
        var proxy=new org.springframework.aop.framework.ProxyFactory(service(instant));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                new DataSourceTransactionManager(api.getDataSource()),
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        return (ParkingLotService)proxy.getProxy();
    }
    org.springframework.test.web.servlet.MockMvc mvc(String instant) {
        // Real controller/advice, consent checks, SQL and annotation-driven transaction proxy.
        // JWT/filter coverage remains in ParkingLotHttpTests; supply the authenticated principal here.
        return org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new ParkingLotController(transactionalService(instant)))
                .setControllerAdvice(new ParkingLotExceptionHandler())
                .setCustomArgumentResolvers(new io.github.yongyong14.parking_server.global.security.CurrentUserResolver()).build();
    }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder httpRequest() {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(user,null,List.of()));
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/parking-lots/nearby")
                .param("lat","37.32").param("lng","127.09");
    }
    @AfterEach void clearSecurityContext() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    @Test void httpSuccessConsentRejectionExpansionAndInvalidInputAgreeWithDatabaseLogs() throws Exception {
        var mvc=mvc("2026-11-06T03:00:00Z");
        UUID id=lot(750,"expanded");
        mvc.perform(httpRequest()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.radiusM").value(1000))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.expanded").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.items[0].id").value(id.toString()));
        assertThat(logs()).isEqualTo(1);
        String audit=api.queryForObject("SELECT row_to_json(l)::text FROM location_access_logs l",String.class);
        assertThat(audit).contains("\"action\":\"USE\"").doesNotContain("37.32","127.09","lat=","lng=");
        assertThat(api.queryForMap("SELECT user_id,parking_event_id,purpose,collection_method,recipient FROM location_access_logs"))
                .containsEntry("user_id",user).containsEntry("parking_event_id",null)
                .containsEntry("purpose","주변 주차장 조회").containsEntry("collection_method","요청 좌표").containsEntry("recipient",null);
        owner.execute("DELETE FROM parking_lots");
        mvc.perform(httpRequest()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.radiusM").value(1000))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.expanded").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.items").isEmpty());
        assertThat(logs()).isEqualTo(2);
        owner.execute("DELETE FROM user_agreements");
        mvc.perform(httpRequest()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.code").value("LOCATION_CONSENT_REQUIRED"));
        assertThat(logs()).isEqualTo(2);
        for(var request:List.of(httpRequest().param("radius","1001"),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/parking-lots/nearby").param("lat","91").param("lng","127.09"),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/parking-lots/nearby").param("lat","37.32").param("lng","181"))) {
            mvc.perform(request).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.code").value("INVALID_INPUT"));
            assertThat(logs()).isEqualTo(2);
        }
    }
    @Test void missingWeekendAndHolidayHoursStayNullInsteadOfFallingBackToWeekday() throws Exception {
        UUID id=lot(10,"no weekend hours");
        owner.update("UPDATE parking_lots SET saturday_open=NULL,saturday_close=NULL,holiday_open=NULL,holiday_close=NULL WHERE id=?",id);
        // Weekday hours exist, but neither Saturday nor Sunday may borrow them.
        for(String instant:List.of("2026-11-07T03:00:00Z","2026-11-08T03:00:00Z"))
            mvc(instant).perform(httpRequest()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.items[0].operatingHours").hasJsonPath())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.items[0].operatingHours").isEmpty());
        owner.execute("INSERT INTO holidays VALUES ('2026-11-06','weekday holiday')");
        mvc("2026-11-06T03:00:00Z").perform(httpRequest()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.items[0].operatingHours").isEmpty());
    }
    @Test void sortedStraightDistanceAndUseLog() {
        UUID farther=lot(300,"farther"),nearer=lot(100,"nearer");
        var result=nearby(500);
        assertThat(result.radiusM()).isEqualTo(500); assertThat(result.expanded()).isFalse();
        assertThat(result.items()).extracting(ParkingLotResponse.Item::id).containsExactly(nearer,farther);
        assertThat(result.items().getFirst().distanceM()).isCloseTo(100,within(0.01));
        assertThat(result.items().getFirst().operatingHours()).isEqualTo("09:00-18:00");
        assertThat(api.queryForMap("SELECT action,purpose,parking_event_id FROM location_access_logs"))
                .containsEntry("action","USE").containsEntry("purpose","주변 주차장 조회").containsEntry("parking_event_id",null);
    }
    @Test void expansionAndEmptyAreSuccessfulAndLoggedOnce() {
        lot(750,"expanded"); lot(1100,"outside");
        var expanded=nearby(500);
        assertThat(expanded.radiusM()).isEqualTo(1000); assertThat(expanded.expanded()).isTrue();
        assertThat(expanded.items()).hasSize(1); assertThat(logs()).isEqualTo(1);
        owner.execute("DELETE FROM parking_lots");
        var empty=nearby(500); assertThat(empty.expanded()).isTrue(); assertThat(empty.items()).isEmpty();
        assertThat(logs()).isEqualTo(2);
    }
    @Test void activationDuringSingleSearchStatementUsesOneSnapshot() throws Exception {
        UUID old=lot(750,"previous snapshot");
        UUID next=UUID.randomUUID(),newLot=UUID.randomUUID();
        owner.update("INSERT INTO data_imports(id,source_dataset,file_name,file_hash) VALUES (?,'yongin_parking_lots','next',?)",next,next.toString());
        owner.update("INSERT INTO parking_lots(id,import_id,source_record_key,name,location) VALUES (?,?,?,'next snapshot',ST_Project(ST_SetSRID(ST_MakePoint(127.09,37.32),4326)::geography,800,0))",
                newLot,next,newLot.toString());
        var searchStatements=new java.util.concurrent.atomic.AtomicInteger();
        // Inject only a test gate into the actual search SQL. Blocking inside the statement establishes
        // its snapshot before activation commits, without relying on sleeps or two application queries.
        var gatedJdbc=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(api.getDataSource()) {
            @Override public <T> T query(String sql,Map<String,?> params,org.springframework.jdbc.core.ResultSetExtractor<T> extractor) {
                searchStatements.incrementAndGet();
                assertThat(sql).isEqualTo(ParkingLotService.NEARBY_SQL);
                sql=sql.replace("WITH search_point AS", "WITH test_gate AS MATERIALIZED (SELECT pg_advisory_xact_lock(291108)), search_point AS")
                        .replace("FROM public.parking_lots p", "FROM test_gate CROSS JOIN public.parking_lots p");
                return super.query(sql,params,extractor);
            }
        };
        var gatedStore=new ParkingStore(api.getDataSource()) {
            @Override public org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc() { return gatedJdbc; }
        };
        var service=new ParkingLotService(gatedStore,new AgreementPolicy("lot-test"),Clock.fixed(Instant.parse("2026-11-06T03:00:00Z"),ZoneOffset.UTC));
        try(var gate=source("parking_migrator").getConnection(); var pool=Executors.newSingleThreadExecutor()) {
            gate.setAutoCommit(false);
            int blocker;
            try(var statement=gate.createStatement()) {
                statement.execute("SELECT pg_advisory_xact_lock(291108)");
                try(var rows=statement.executeQuery("SELECT pg_backend_pid()")) { rows.next(); blocker=rows.getInt(1); }
            }
            var request=pool.submit(()->tx.execute(s->service.nearby(user,37.32,127.09,500)));
            try {
                awaitBlockedBy(blocker);
                new TransactionTemplate(new DataSourceTransactionManager(owner.getDataSource())).executeWithoutResult(s->{
                    owner.update("UPDATE data_imports SET active=false WHERE id=?",imp);
                    owner.update("UPDATE data_imports SET active=true WHERE id=?",next);
                });
            } finally { gate.commit(); }
            var result=request.get(5,TimeUnit.SECONDS);
            assertThat(result.expanded()).isTrue();
            assertThat(result.items()).extracting(ParkingLotResponse.Item::id).containsExactly(old);
        }
        assertThat(searchStatements.get()).as("active import, initial radius and expansion use one SQL statement").isEqualTo(1);
        assertThat(nearby(500).items()).extracting(ParkingLotResponse.Item::id).containsExactly(newLot);
        assertThat(logs()).isEqualTo(2);
    }
    @Test void explicitRadiusDoesNotInventExpansionOrLimitOrCityBoundary() {
        lot(700,"not in custom radius");
        var custom=nearby(600); assertThat(custom.radiusM()).isEqualTo(600); assertThat(custom.expanded()).isFalse(); assertThat(custom.items()).isEmpty();
        assertThat(nearby(1000).expanded()).isFalse();
        for(int i=0;i<105;i++) lot(10+i,"many");
        assertThat(nearby(500).items()).hasSize(105);
        var outside=tx.execute(s->service("2026-11-06T03:00:00Z").nearby(user,0,0,1));
        assertThat(outside.items()).isEmpty();
        ParkingLotService.validate(-90,-180,1); ParkingLotService.validate(90,180,1000);
    }
    @Test void onlyActiveParkingDatasetIsVisibleAndMissingValuesStayNull() {
        UUID id=lot(10,"null fields");
        owner.update("UPDATE parking_lots SET fee_type=NULL,fee_info=NULL,capacity=NULL,weekday_open=NULL,weekday_close=NULL,address=NULL WHERE id=?",id);
        var item=nearby(500).items().getFirst();
        assertThat(item.feeType()).isNull(); assertThat(item.feeInfo()).isNull(); assertThat(item.capacity()).isNull();
        assertThat(item.operatingHours()).isNull(); assertThat(item.address()).isNull();
        owner.update("UPDATE data_imports SET active=false WHERE id=?",imp);
        assertThat(nearby(500).items()).isEmpty();
        owner.update("UPDATE data_imports SET source_dataset='different',active=true WHERE id=?",imp);
        assertThat(nearby(500).items()).isEmpty();
    }
    @Test void kstDateSaturdaySundayHolidayPriorityAndOvernight() {
        UUID id=lot(10,"hours");
        for(var entry:Map.of("2026-11-06T14:59:59Z","09:00-18:00","2026-11-06T15:00:00Z","10:00-19:00",
                "2026-11-07T15:00:00Z","11:00-20:00").entrySet())
            assertThat(tx.execute(s->service(entry.getKey()).nearby(user,37.32,127.09,500)).items().getFirst().operatingHours()).isEqualTo(entry.getValue());
        owner.update("INSERT INTO holidays VALUES ('2026-11-07','fixture')");
        assertThat(tx.execute(s->service("2026-11-07T03:00:00Z").nearby(user,37.32,127.09,500)).items().getFirst().operatingHours()).isEqualTo("11:00-20:00");
        owner.update("UPDATE parking_lots SET holiday_open='22:00',holiday_close='06:00' WHERE id=?",id);
        assertThat(tx.execute(s->service("2026-11-07T03:00:00Z").nearby(user,37.32,127.09,500)).items().getFirst().operatingHours()).isEqualTo("22:00-06:00");
    }
    @Test void latestAcceptedConsentRequiredAndRejectedInputsHaveNoLogs() {
        for(String sql:List.of("DELETE FROM user_agreements","INSERT INTO user_agreements(user_id,agreement_type,version,action) VALUES ('"+user+"','LOCATION_SERVICE','old','AGREE')",
                "INSERT INTO user_agreements(user_id,agreement_type,version,action) VALUES ('"+user+"','LOCATION_SERVICE','lot-test','WITHDRAW')")) {
            owner.execute(sql);
            assertThatThrownBy(()->nearby(500)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LOCATION_CONSENT_REQUIRED));
        }
        for (int radius:new int[]{0,-1,1001}) assertThatThrownBy(()->nearby(radius)).isInstanceOf(BusinessException.class);
        assertThat(logs()).isZero();
    }
    @Test void logFailureRollsBackRequestAndGiSTIndexIsAvailable() {
        lot(100,"index fixture");
        owner.execute("CREATE FUNCTION reject_lot_log() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture'; END $$; CREATE TRIGGER reject_lot_log BEFORE INSERT ON location_access_logs FOR EACH ROW EXECUTE FUNCTION reject_lot_log()");
        try { assertThatThrownBy(()->nearby(500)).isInstanceOf(org.springframework.dao.DataAccessException.class); }
        finally { owner.execute("DROP TRIGGER reject_lot_log ON location_access_logs; DROP FUNCTION reject_lot_log()"); }
        assertThat(logs()).isZero();
        // With one row the import B-tree is cheaper than GiST. Use a selective spatial fixture
        // and real statistics to verify the actual combined query's spatial access path.
        owner.update("""
                INSERT INTO parking_lots(id,import_id,source_record_key,name,location)
                SELECT gen_random_uuid(),?,'far-'||n,'far fixture',ST_SetSRID(ST_MakePoint(128,36),4326)::geography
                FROM generate_series(1,2000) n
                """,imp);
        owner.execute("ANALYZE parking_lots; ANALYZE data_imports");
        var plan=tx.execute(s->{
            return store.jdbc().query("EXPLAIN "+ParkingLotService.NEARBY_SQL,
                    Map.of("lat",37.32,"lng",127.09,"radius",500,"day",LocalDate.of(2026,11,6)),(rs,n)->rs.getString(1));
        });
        assertThat(String.join("\n",plan)).contains("ix_parking_lots_location");
    }
    @Test void userShareLockBlocksConcurrentWithdrawal() throws Exception {
        var readComplete=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var request=pool.submit(()->tx.execute(s->{
                var result=service("2026-11-06T03:00:00Z").nearby(user,37.32,127.09,500);
                readComplete.countDown();
                try { if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return result;
            }));
            assertThat(readComplete.await(5,TimeUnit.SECONDS)).isTrue();
            try {
            try(var other=source("parking_api").getConnection()) {
                other.setAutoCommit(false);
                try(var timeout=other.createStatement()) { timeout.execute("SET LOCAL lock_timeout='200ms'"); }
                assertThatThrownBy(()->{ try(var statement=other.prepareStatement("SELECT id FROM users WHERE id=? FOR UPDATE")) { statement.setObject(1,user); statement.executeQuery(); } })
                        .isInstanceOf(java.sql.SQLException.class);
                other.rollback();
            }
            } finally { release.countDown(); }
            assertThat(request.get(5,TimeUnit.SECONDS)).isNotNull();
        }
    }
    @Test void withdrawalCommittedWhileLookupWaitsMustPreventUse() throws Exception {
        // Reverse the existing concurrency test: withdrawal owns FOR UPDATE first.
        // It only appends an agreement event, matching the documented lock protocol.
        try(var withdrawal=source("parking_api").getConnection(); var pool=Executors.newSingleThreadExecutor()) {
            withdrawal.setAutoCommit(false);
            int blocker;
            try(var statement=withdrawal.createStatement(); var rows=statement.executeQuery("SELECT pg_backend_pid()")) {
                rows.next(); blocker=rows.getInt(1);
            }
            try(var statement=withdrawal.prepareStatement("SELECT id FROM users WHERE id=? FOR UPDATE")) {
                statement.setObject(1,user); statement.executeQuery();
            }
            try(var statement=withdrawal.prepareStatement("INSERT INTO user_agreements(user_id,agreement_type,version,action) VALUES (?,'LOCATION_SERVICE','lot-test','WITHDRAW')")) {
                statement.setObject(1,user); statement.executeUpdate();
            }
            var service=transactionalService("2026-11-06T03:00:00Z");
            var request=pool.submit(()->{
                try { service.nearby(user,37.32,127.09,500); return "SUCCESS"; }
                catch(BusinessException e) { return e.getErrorCode().getCode(); }
            });
            try {
                awaitBlockedBy(blocker);
            } finally { withdrawal.commit(); }
            String outcome=request.get(5,TimeUnit.SECONDS);
            assertThat(api.queryForObject("SELECT action FROM user_agreements WHERE user_id=? ORDER BY id DESC LIMIT 1",String.class,user)).isEqualTo("WITHDRAW");
            assertThat(outcome).as("latest committed withdrawal must be visible after acquiring FOR SHARE; USE rows=%s",logs())
                    .isEqualTo("LOCATION_CONSENT_REQUIRED");
            assertThat(logs()).isZero();
        }
    }
    void awaitBlockedBy(int blocker) throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline) {
            if(Boolean.TRUE.equals(owner.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM pg_stat_activity a WHERE ?=ANY(pg_blocking_pids(a.pid)))",Boolean.class,blocker))) return;
            Thread.sleep(20);
        }
        fail("request must be waiting for the test blocker before its commit");
    }
}
