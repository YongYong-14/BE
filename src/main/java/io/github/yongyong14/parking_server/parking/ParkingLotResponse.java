package io.github.yongyong14.parking_server.parking;

import java.util.List;
import java.util.UUID;

public record ParkingLotResponse(int radiusM, boolean expanded, List<Item> items) {
    public record Location(double lat, double lng) {}
    public record Item(UUID id, String name, String address, double distanceM, Location location,
                       String parkingType, String feeType, String feeInfo, String operatingHours, Integer capacity) {}
}
