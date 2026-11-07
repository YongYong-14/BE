package io.github.yongyong14.parking_server.auditingfixture;

import io.github.yongyong14.parking_server.global.entity.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "audit_created", schema = "auditing_test")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreatedRecord extends BaseCreatedEntity {
    @Id
    private Long id;

    @Column(nullable = false)
    private String label;

    public CreatedRecord(Long id, String label) {
        this.id = id;
        this.label = label;
    }

    public void rename(String label) {
        this.label = label;
    }
}
