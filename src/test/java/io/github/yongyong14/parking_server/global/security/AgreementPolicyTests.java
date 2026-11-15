package io.github.yongyong14.parking_server.global.security;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;

class AgreementPolicyTests {
    @Test void onlyFirstVersionIsCurrentEvenWhenOlderVersionsMayBeRecorded() {
        var policy=new AgreementPolicy("test-current,test-previous");
        assertThat(policy.acceptsLocationVersion("test-current")).isTrue();
        assertThat(policy.acceptsLocationVersion("test-previous")).isFalse();
        assertThat(policy.acceptsLocationVersion("unknown")).isFalse();
        assertThat(policy.acceptsLocationVersion(null)).isFalse();
        assertThat(policy.acceptsLocationVersion("")).isFalse();
    }
    @Test void emptyBlankAndNullConfigurationFailClosed() {
        for (String csv:new String[]{null,"","   ",", ,\t,"}) {
            var policy=new AgreementPolicy(csv);
            assertThat(policy.locationServiceVersions()).isEmpty();
            assertThat(policy.acceptsLocationVersion("test-current")).isFalse();
            assertThat(policy.acceptsLocationVersion(null)).isFalse();
        }
    }
    @Test void parsingTrimsDropsEmptyAndDeduplicatesInFirstOccurrenceOrder() {
        var policy=new AgreementPolicy(" , test-current ,test-previous,, test-current, test-oldest , test-previous, ");
        assertThat(policy.locationServiceVersions()).containsExactly("test-current","test-previous","test-oldest");
        assertThat(policy.acceptsLocationVersion("test-current")).isTrue();
        assertThat(policy.acceptsLocationVersion("test-previous")).isFalse();
        assertThat(policy.acceptsLocationVersion(" test-current ")).isFalse(); // match #22's exact event version comparison
        assertThatThrownBy(()->policy.locationServiceVersions().add("new-version")).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void authLocationVersionsAloneBindsThroughActualApplicationYaml() throws Exception {
        try (var context=context(Map.of("AUTH_LOCATION_VERSIONS"," test-current, test-previous, test-current "))) {
            var policy=context.getBean(AgreementPolicy.class);
            assertThat(context.getEnvironment().getProperty("app.account.location-versions"))
                    .isEqualTo(" test-current, test-previous, test-current ");
            assertThat(policy.locationServiceVersions()).isEqualTo(List.of("test-current","test-previous"));
            assertThat(policy.acceptsLocationVersion("test-current")).isTrue();
            assertThat(policy.acceptsLocationVersion("test-previous")).isFalse();
        }
    }
    @Test void applicationYamlHasNoDefaultAgreementVersion() throws Exception {
        try (var context=context(Map.of())) {
            assertThat(context.getBean(AgreementPolicy.class).locationServiceVersions()).isEmpty();
        }
    }
    private AnnotationConfigApplicationContext context(Map<String,Object> env) throws Exception {
        var environment=new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("isolated-test-environment",env));
        for (var source:new YamlPropertySourceLoader().load("application",new ClassPathResource("application.yaml")))
            environment.getPropertySources().addLast(source);
        var context=new AnnotationConfigApplicationContext();
        context.setEnvironment(environment);
        context.register(AgreementPolicy.class);
        context.refresh();
        return context;
    }
}
