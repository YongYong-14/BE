package io.github.yongyong14.parking_server.auditing;

import io.github.yongyong14.parking_server.auditingfixture.CreatedRecord;
import io.github.yongyong14.parking_server.auditingfixture.TimeRecord;
import io.github.yongyong14.parking_server.global.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import java.sql.Connection;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(AuditingPostgresTest.JpaTestConfig.class)
@Transactional
class AuditingPostgresTest {
    // PostgreSQL stores microseconds: avoid nanosecond rounding and sleeps.
    private static final Instant CREATED = Instant.parse("2026-01-02T03:04:05.123456Z");
    private static final Instant UPDATED = Instant.parse("2026-01-02T04:05:06.654321Z");

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoBean(name = "auditingDateTimeProvider")
    private DateTimeProvider provider;

    @BeforeEach
    void setClock() {
        when(provider.getNow()).thenReturn(Optional.of(CREATED));
        clearInvocations(provider);
    }

    @Test
    void createdOnlyRecordsCreationAndKeepsItWhenBusinessFieldChanges() {
        var record = new CreatedRecord(1L, "before");
        entityManager.persist(record);
        entityManager.flush();
        entityManager.clear();
        var loaded = entityManager.find(CreatedRecord.class, 1L);
        assertThat(loaded.getCreatedAt()).isEqualTo(CREATED);

        when(provider.getNow()).thenReturn(Optional.of(UPDATED));
        loaded.rename("after");
        entityManager.flush();
        entityManager.clear();
        assertThat(entityManager.find(CreatedRecord.class, 1L).getCreatedAt()).isEqualTo(CREATED);
    }

    @Test
    void creationSetsBothNotNullTimestampsFromOneInstant() {
        UUID id = UUID.randomUUID();
        entityManager.persist(new TimeRecord(id, "new"));
        entityManager.flush();
        entityManager.clear();
        var loaded = entityManager.find(TimeRecord.class, id);
        assertThat(loaded.getCreatedAt()).isEqualTo(CREATED);
        assertThat(loaded.getUpdatedAt()).isEqualTo(CREATED);
        verify(provider, times(1)).getNow();
    }

    @Test
    void dirtyUpdatePreservesCreatedAtAndAdvancesUpdatedAtAfterReload() {
        UUID id = UUID.randomUUID();
        entityManager.persist(new TimeRecord(id, "before"));
        entityManager.flush();
        entityManager.clear();

        var loaded = entityManager.find(TimeRecord.class, id);
        when(provider.getNow()).thenReturn(Optional.of(UPDATED));
        loaded.rename("after");
        entityManager.flush();
        entityManager.clear();

        var updated = entityManager.find(TimeRecord.class, id);
        assertThat(updated.getLabel()).isEqualTo("after");
        assertThat(updated.getCreatedAt()).isEqualTo(CREATED);
        assertThat(updated.getUpdatedAt()).isEqualTo(UPDATED).isAfter(updated.getCreatedAt());
        // Listener must not be duplicated by the superclass hierarchy.
        verify(provider, times(2)).getNow();
    }

    @Test
    void bulkUpdateBypassesAuditingAndRequiresAnExplicitTimestamp() {
        UUID id = UUID.randomUUID();
        entityManager.persist(new TimeRecord(id, "before"));
        entityManager.flush();
        entityManager.clear();
        when(provider.getNow()).thenReturn(Optional.of(UPDATED));

        entityManager.createQuery("update TimeRecord r set r.label = :label where r.id = :id")
            .setParameter("label", "bulk").setParameter("id", id).executeUpdate();
        entityManager.clear();
        assertThat(entityManager.find(TimeRecord.class, id).getUpdatedAt()).isEqualTo(CREATED);

        entityManager.createQuery("update TimeRecord r set r.label = :label, r.updatedAt = :now where r.id = :id")
            .setParameter("label", "explicit").setParameter("now", UPDATED).setParameter("id", id).executeUpdate();
        entityManager.clear();
        var updated = entityManager.find(TimeRecord.class, id);
        assertThat(updated.getCreatedAt()).isEqualTo(CREATED);
        assertThat(updated.getUpdatedAt()).isEqualTo(UPDATED);
        verify(provider, times(1)).getNow();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(JpaAuditingConfig.class)
    static class JpaTestConfig {
        @Bean
        DataSource dataSource() throws Exception {
            String url = required("AUDITING_TEST_JDBC_URL");
            if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:\\d+/auditing_verify\\?currentSchema=auditing_test")) {
                throw new IllegalStateException("Expected the isolated auditing DB on loopback");
            }
            var source = new DriverManagerDataSource(url, "auditing_tester", required("AUDITING_TEST_PASSWORD"));
            // Attest before any test DDL. Environment variables do not configure Boot/JPA implicitly.
            try (Connection connection = source.getConnection(); var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT current_database(), current_user, current_setting('verification.identity', true)")) {
                if (!rows.next() || !"auditing_verify".equals(rows.getString(1))
                    || !"auditing_tester".equals(rows.getString(2))
                    || !required("AUDITING_TEST_IDENTITY").equals(rows.getString(3))) {
                    throw new IllegalStateException("Isolated auditing database identity mismatch");
                }
            }
            new ResourceDatabasePopulator(new ClassPathResource("auditing-schema.sql")).execute(source);
            return source;
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan("io.github.yongyong14.parking_server.auditingfixture");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate",
                "hibernate.default_schema", "auditing_test", "hibernate.jdbc.time_zone", "UTC"));
            return factory;
        }

        @Bean
        JpaTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        private static String required(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("Run scripts/db/Verify-Auditing.ps1; missing " + name);
            }
            return value;
        }
    }
}
