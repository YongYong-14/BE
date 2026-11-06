package io.github.yongyong14.parking_server.vehicle;

import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

public record VehicleResponse(@Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED) Details vehicle) {
    static VehicleResponse from(Vehicle vehicle) {
        return new VehicleResponse(vehicle == null ? null : new Details(vehicle.getId(), vehicle.getName(), vehicle.getBluetoothAddress(), vehicle.getDeviceType()));
    }
    public record Details(UUID id, String name, String bluetoothAddress, @Schema(nullable = true) String deviceType) {
        @Override public String toString() { return "VehicleDetails[redacted]"; }
    }
}
