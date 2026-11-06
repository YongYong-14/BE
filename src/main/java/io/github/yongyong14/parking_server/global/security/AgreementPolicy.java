package io.github.yongyong14.parking_server.global.security;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Account-owned CSV: all entries may be recorded by the account API; only the first is current. */
@Component
public final class AgreementPolicy {
    private final List<String> locationServiceVersions;

    public AgreementPolicy(@Value("${app.account.location-versions:}") String csv) {
        locationServiceVersions = csv == null ? List.of() : Arrays.stream(csv.split(","))
                .map(String::trim).filter(version -> !version.isEmpty()).distinct().toList();
    }
    public List<String> locationServiceVersions() { return locationServiceVersions; }

    public boolean acceptsLocationVersion(String version) {
        return version != null && !locationServiceVersions.isEmpty() && locationServiceVersions.getFirst().equals(version);
    }
}
