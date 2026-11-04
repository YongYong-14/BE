package io.github.yongyong14.parking_server.global.config;

import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider", modifyOnCreate = true)
@ConditionalOnProperty(name = "app.jpa-auditing.enabled", havingValue = "true", matchIfMissing = true)
public class JpaAuditingConfig {

    @Bean
    DateTimeProvider auditingDateTimeProvider() {
        // Supply an absolute instant, independent of the JVM's default time zone.
        return () -> Optional.of(Instant.now());
    }
}
