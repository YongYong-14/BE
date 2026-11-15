package io.github.yongyong14.parking_server.account;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "user_agreements")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class UserAgreement {
    enum Type { TERMS, PRIVACY, LOCATION_SERVICE }
    enum Action { AGREE, WITHDRAW }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private UUID userId;
    @Enumerated(EnumType.STRING)
    private Type agreementType;
    private String version;
    @Enumerated(EnumType.STRING)
    private Action action;
    private Instant occurredAt;

    UserAgreement(UUID userId, Type type, String version, Action action, Instant now) {
        this.userId = userId;
        this.agreementType = type;
        this.version = version;
        this.action = action;
        this.occurredAt = now;
    }
}
