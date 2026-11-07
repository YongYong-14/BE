package io.github.yongyong14.parking_server.account;

import io.github.yongyong14.parking_server.global.exception.BusinessException;
import io.github.yongyong14.parking_server.global.exception.ErrorCode;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AgreementService {
    private final UserAgreementRepository agreements;
    private final UserRepository users;
    private final Map<UserAgreement.Type, List<String>> versions;
    private final Clock clock;

    AgreementService(UserAgreementRepository agreements, UserRepository users, Clock clock,
            @Value("${app.account.terms-versions:}") String terms,
            @Value("${app.account.privacy-versions:}") String privacy,
            @Value("${app.account.location-versions:}") String location) {
        this.agreements = agreements;
        this.users = users;
        this.clock = clock;
        versions = Map.of(UserAgreement.Type.TERMS, parse(terms), UserAgreement.Type.PRIVACY, parse(privacy),
                UserAgreement.Type.LOCATION_SERVICE, parse(location));
    }

    AccountApi.MissingAgreements missing(UUID userId) {
        var latest = agreements.findLatestByType(userId);
        return new AccountApi.MissingAgreements(Arrays.stream(UserAgreement.Type.values())
                .filter(type -> !acceptedCurrent(type, latest)).toList());
    }

    List<AccountApi.Agreement> latest(UUID userId) {
        return agreements.findLatestByType(userId).stream().map(AccountApi.Agreement::from).toList();
    }

    @Transactional
    public AccountApi.Agreement record(UUID userId, AccountApi.AgreementRequest request) {
        users.lockById(userId).orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        if (request.action() == UserAgreement.Action.AGREE && !versions.get(request.type()).contains(request.version())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        if (request.type() == UserAgreement.Type.LOCATION_SERVICE && request.action() == UserAgreement.Action.WITHDRAW) {
            // The spec couples a successful withdrawal to data destruction. The policy and
            // runtime permissions for that destruction are still pending (spec item #4).
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        return AccountApi.Agreement.from(agreements.save(new UserAgreement(userId, request.type(),
                request.version(), request.action(), clock.instant())));
    }

    private boolean acceptedCurrent(UserAgreement.Type type, List<UserAgreement> latest) {
        var allowed = versions.get(type);
        return !allowed.isEmpty() && latest.stream().anyMatch(row -> row.getAgreementType() == type
                && row.getAction() == UserAgreement.Action.AGREE && allowed.getFirst().equals(row.getVersion()));
    }

    private static List<String> parse(String csv) {
        return Arrays.stream(csv.split(",")).map(String::trim).filter(v -> !v.isEmpty()).distinct().toList();
    }
}
