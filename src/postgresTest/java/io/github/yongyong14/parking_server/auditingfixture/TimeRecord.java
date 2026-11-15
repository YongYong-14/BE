package io.github.yongyong14.parking_server.auditingfixture;

import io.github.yongyong14.parking_server.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "audit_time", schema = "auditing_test")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TimeRecord extends BaseTimeEntity {
    @Id
    private UUID id;

    @Column(nullable = false)
    private String label;

    public TimeRecord(UUID id, String label) {
        this.id = id;
        this.label = label;
    }

    public void rename(String label) {
        this.label = label;
    }
}
