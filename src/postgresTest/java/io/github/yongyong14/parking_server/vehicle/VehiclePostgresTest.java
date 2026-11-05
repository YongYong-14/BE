package io.github.yongyong14.parking_server.vehicle;

import io.github.yongyong14.parking_server.global.config.JpaAuditingConfig;
import io.github.yongyong14.parking_server.global.exception.*;
import jakarta.persistence.EntityManagerFactory;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringJUnitConfig(VehiclePostgresTest.Config.class)
class VehiclePostgresTest {
    @Autowired VehicleService service;
    @Autowired ProbeRepository repository;
    @Autowired DataSource source;
    @Autowired JpaTransactionManager manager;
    UUID user;
    JdbcClient jdbc;
    static VehicleRequest request(String mac, String name) { return new VehicleRequest(name, mac, null); }
    static final String A = "AA:BB:CC:DD:EE:FF", B = "00:11:22:33:44:55";
    @BeforeEach void setup() {
        repository.barrier = null;
        repository.failInsert = false;
        jdbc = JdbcClient.create(source);
        user = UUID.randomUUID();
        jdbc.sql("INSERT INTO users(id,kakao_id) VALUES (:id,:kakao)").param("id", user).param("kakao", ThreadLocalRandom.current().nextLong()).update();
    }
    @Test void registrationLookupAndSameMacUpdateKeepIdentityAndCreation() {
        assertThat(service.get(user).vehicle()).isNull();
        var first = service.put(user, request(A, "before")).vehicle();
        var created = jdbc.sql("SELECT created_at FROM vehicles WHERE id=:id").param("id", first.id()).query(java.time.OffsetDateTime.class).single();
        var updated = service.put(user, new VehicleRequest("after", A, "arbitrary")).vehicle();
        assertThat(updated.id()).isEqualTo(first.id());
        assertThat(updated.deviceType()).isEqualTo("arbitrary");
        assertThat(service.get(user).vehicle()).isEqualTo(updated);
        assertThat(jdbc.sql("SELECT created_at FROM vehicles WHERE id=:id").param("id", first.id()).query(java.time.OffsetDateTime.class).single()).isEqualTo(created);
    }
    @Test void replacementPreservesParkingReferenceAndNeverRestoresOldRow() {
        var old = service.put(user, request(A, "before")).vehicle();
        UUID event = UUID.randomUUID();
        jdbc.sql("INSERT INTO parking_events(id,user_id,vehicle_id,client_event_id,request_hash,vehicle_name_snapshot,detected_at,confirmed_at,confirmed_location,confirmation_method) VALUES (:id,:user,:vehicle,:client,'test','snapshot',now(),now(),ST_SetSRID(ST_MakePoint(127,37),4326)::geography,'CONFIRMED')")
                .param("id", event).param("user", user).param("vehicle", old.id()).param("client", UUID.randomUUID()).update();
        service.put(user, request(A, "renamed"));
        var replacement = service.put(user, request(B, "new")).vehicle();
        assertThat(replacement.id()).isNotEqualTo(old.id());
        assertThat(jdbc.sql("SELECT deleted_at IS NOT NULL FROM vehicles WHERE id=:id").param("id", old.id()).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT vehicle_id FROM parking_events WHERE id=:id").param("id", event).query(UUID.class).single()).isEqualTo(old.id());
        assertThat(jdbc.sql("SELECT vehicle_name_snapshot FROM parking_events WHERE id=:id").param("id", event).query(String.class).single()).isEqualTo("snapshot");
        assertThat(service.put(user, request(A, "again")).vehicle().id()).isNotIn(old.id(), replacement.id());
        assertThat(jdbc.sql("SELECT count(*) FROM vehicles WHERE user_id=:id").param("id", user).query(Long.class).single()).isEqualTo(3);
    }
    @Test void failedNewInsertRollsBackRetirement() {
        var old = service.put(user, request(A, "before")).vehicle();
        repository.failInsert = true;
        assertThatThrownBy(() -> service.put(user, request(B, "new"))).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException)e).getErrorCode()).isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR);
        repository.failInsert = false;
        assertThat(service.get(user).vehicle()).isEqualTo(old);
    }
    @Test void isolatesUsersAndExcludesRetiredVehicles() {
        var first = service.put(user, request(A, "first")).vehicle();
        UUID other = UUID.randomUUID();
        jdbc.sql("INSERT INTO users(id,kakao_id) VALUES (:id,:kakao)").param("id", other).param("kakao", ThreadLocalRandom.current().nextLong()).update();
        assertThat(service.get(other).vehicle()).isNull();
        var second = service.put(other, request(A, "second")).vehicle();
        assertThat(second.id()).isNotEqualTo(first.id());
        jdbc.sql("UPDATE vehicles SET deleted_at=now() WHERE id=:id").param("id", first.id()).update();
        assertThat(service.get(user).vehicle()).isNull();
        assertThat(service.get(other).vehicle()).isEqualTo(second);
    }
    @Test void concurrentFirstRegistrationUsesPartialUnique() throws Exception { race(false, false); }
    @Test void concurrentSameMacUpdatesRejectLostUpdate() throws Exception { race(true, false); }
    @Test void concurrentReplacementRejectsStaleRetirement() throws Exception { race(true, true); }
    private void race(boolean existing, boolean replace) throws Exception {
        if (existing) service.put(user, request(A, "initial"));
        repository.barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = List.of(pool.submit(() -> attempt(request(replace ? B : A, "one"))),
                    pool.submit(() -> attempt(request(replace ? B : A, "two"))));
            var outcomes = List.of(futures.get(0).get(15, TimeUnit.SECONDS), futures.get(1).get(15, TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder("ok", "VEHICLE_CONFLICT");
        } finally { repository.barrier = null; }
        assertThat(jdbc.sql("SELECT count(*) FROM vehicles WHERE user_id=:id AND deleted_at IS NULL").param("id", user).query(Long.class).single()).isEqualTo(1);
    }
    String attempt(VehicleRequest request) {
        try { service.put(user, request); return "ok"; }
        catch (BusinessException e) { return e.getErrorCode().getCode(); }
    }
    @Test void parkingShareLockBlocksReplacementUntilCommit() throws Exception {
        var old = service.put(user, request(A, "initial")).vehicle();
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var parking = pool.submit(() -> new TransactionTemplate(manager).execute(status -> {
                jdbc.sql("SELECT id FROM users WHERE id=:id FOR SHARE").param("id", user).query(UUID.class).single();
                jdbc.sql("SELECT id FROM vehicles WHERE id=:id AND deleted_at IS NULL FOR SHARE").param("id", old.id()).query(UUID.class).single();
                locked.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException e) { throw new IllegalStateException(e); }
                return null;
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var replacement = pool.submit(() -> service.put(user, request(B, "replacement")));
            try {
                assertThatThrownBy(() -> replacement.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally { release.countDown(); }
            parking.get(10, TimeUnit.SECONDS);
            assertThat(replacement.get(10, TimeUnit.SECONDS).vehicle().id()).isNotEqualTo(old.id());
        } finally { release.countDown(); }
    }
    static class ProbeRepository extends VehicleRepository {
        volatile CyclicBarrier barrier;
        volatile boolean failInsert;
        @Override Vehicle active(UUID id) {
            Vehicle found = super.active(id);
            CyclicBarrier gate = barrier;
            if (gate != null) try { gate.await(10, TimeUnit.SECONDS); }
            catch (Exception e) { throw new IllegalStateException(e); }
            return found;
        }
        @Override void save(Vehicle vehicle) {
            if (failInsert) {
                // Force a real PostgreSQL NOT NULL failure after the retirement flush.
                super.save(new Vehicle(vehicle.getUserId(), new VehicleRequest(null, B, null)));
            } else super.save(vehicle);
        }
    }
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(JpaAuditingConfig.class)
    static class Config {
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean ProbeRepository repository() { return new ProbeRepository(); }
        @Bean VehicleTransactions transactions(ProbeRepository repo, Clock clock) { return new VehicleTransactions(repo, clock); }
        @Bean VehicleService service(VehicleTransactions tx) { return new VehicleService(tx); }
        @Bean DataSource dataSource() throws Exception {
            String url = required("VEHICLE_TEST_JDBC_URL");
            if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:\\d+/vehicle_verify")) throw new IllegalStateException("Expected isolated vehicle DB");
            var source = new DriverManagerDataSource(url, "vehicle_tester", required("VEHICLE_TEST_PASSWORD"));
            var connectionProperties = new Properties();
            connectionProperties.setProperty("logServerErrorDetail", "false");
            source.setConnectionProperties(connectionProperties);
            var jdbc = JdbcClient.create(source);
            assertThat(jdbc.sql("SELECT current_database() || ':' || current_user || ':' || current_setting('verification.identity',true)").query(String.class).single())
                    .isEqualTo("vehicle_verify:vehicle_tester:" + required("VEHICLE_TEST_IDENTITY"));
            String v2 = new ClassPathResource("db/migration/V2__erd_v5_schema.sql").getContentAsString(StandardCharsets.UTF_8);
            // Apply actual domain DDL, indexes and FKs verbatim, after identity attestation.
            for (String table : List.of("users", "vehicles", "parking_events")) {
                int start = v2.indexOf("CREATE TABLE \"" + table + "\"");
                jdbc.sql(v2.substring(start, v2.indexOf(';', start) + 1)).update();
            }
            for (String statement : v2.split(";")) {
                String sql = statement.trim();
                if (sql.startsWith("CREATE UNIQUE INDEX ON \"vehicles\"") || sql.startsWith("ALTER TABLE \"vehicles\" ADD FOREIGN KEY")
                        || sql.startsWith("ALTER TABLE \"parking_events\" ADD FOREIGN KEY (\"vehicle_id\"")
                        || sql.startsWith("ALTER TABLE \"parking_events\" ADD FOREIGN KEY (\"user_id\"")) jdbc.sql(sql).update();
            }
            String v3 = new ClassPathResource("db/migration/V3__erd_v5_guards_and_indexes.sql").getContentAsString(StandardCharsets.UTF_8);
            int start = v3.indexOf("CREATE UNIQUE INDEX uq_vehicles_active_user");
            jdbc.sql(v3.substring(start, v3.indexOf(';', start) + 1)).update();
            return source;
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan("io.github.yongyong14.parking_server.vehicle");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate", "hibernate.jdbc.time_zone", "UTC"));
            return factory;
        }
        @Bean JpaTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
        static String required(String key) {
            String value = System.getenv(key);
            if (value == null || value.isBlank()) throw new IllegalStateException("Run Verify-Vehicle.ps1; missing " + key);
            return value;
        }
    }
}
