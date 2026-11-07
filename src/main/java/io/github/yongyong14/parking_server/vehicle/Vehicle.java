package io.github.yongyong14.parking_server.vehicle;

import io.github.yongyong14.parking_server.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.OptimisticLocking;
import org.hibernate.annotations.OptimisticLockType;

@Entity
@Table(name = "vehicles", schema = "public")
@DynamicUpdate
@OptimisticLocking(type = OptimisticLockType.ALL)
@Getter
public class Vehicle extends BaseTimeEntity {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false, updatable = false) private UUID userId;
    @Column(nullable = false, length = 100) private String name;
    @Column(name = "bluetooth_address", nullable = false, length = 17, updatable = false) private String bluetoothAddress;
    @Column(name = "device_type", length = 50) private String deviceType;
    @Column(name = "deleted_at") private Instant deletedAt;

    protected Vehicle() {}
    Vehicle(UUID userId, VehicleRequest request) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.bluetoothAddress = request.bluetoothAddress();
        change(request);
    }
    void change(VehicleRequest request) { name = request.name(); deviceType = request.deviceType(); }
    void retire(Instant now) { deletedAt = now; }
}
