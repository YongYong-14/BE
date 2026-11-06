package io.github.yongyong14.parking_server.account;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "notification_settings")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class NotificationSettings {
    @Id
    private UUID userId;
    private boolean cautionEnabled = true;
    private boolean nightEnabled;
    @LastModifiedDate
    private Instant updatedAt;

    NotificationSettings(UUID userId) {
        this.userId = userId;
    }

    void update(Boolean caution, Boolean night) {
        if (caution != null) cautionEnabled = caution;
        if (night != null) nightEnabled = night;
    }
}
