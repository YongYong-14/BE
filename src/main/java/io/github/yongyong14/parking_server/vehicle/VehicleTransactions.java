package io.github.yongyong14.parking_server.vehicle;

import io.github.yongyong14.parking_server.global.exception.*;
import java.time.Clock;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Lazy
public class VehicleTransactions {
    private final VehicleRepository repository;
    private final Clock clock;
    public VehicleTransactions(@Lazy VehicleRepository repository, Clock clock) { this.repository = repository; this.clock = clock; }
    @Transactional(readOnly = true)
    public VehicleResponse get(UUID userId) { return VehicleResponse.from(repository.active(userId)); }
    @Transactional
    public VehicleResponse put(UUID userId, VehicleRequest request) {
        if (!repository.shareUser(userId)) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        Vehicle current = repository.active(userId);
        if (current != null && current.getBluetoothAddress().equals(request.bluetoothAddress())) {
            current.change(request);
        } else {
            if (current != null) {
                current.retire(clock.instant());
                repository.flush();
            }
            current = new Vehicle(userId, request);
            repository.save(current);
        }
        repository.flush();
        return VehicleResponse.from(current);
    }
}
