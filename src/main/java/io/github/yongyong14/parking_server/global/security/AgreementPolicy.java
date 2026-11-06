package io.github.yongyong14.parking_server.global.security;

import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Account-owned current accepted versions. Empty configuration fails closed, never guesses a version. */
@Component
public record AgreementPolicy(@Value("${app.agreements.location-service.accepted-versions:}") Set<String> locationServiceVersions) {
    public AgreementPolicy { locationServiceVersions = Set.copyOf(locationServiceVersions); }
    public boolean acceptsLocationVersion(String version) {
        return version != null && !version.isBlank() && locationServiceVersions.contains(version);
    }
}
