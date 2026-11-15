package io.github.yongyong14.parking_server.parking;

import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import io.github.yongyong14.parking_server.global.exception.*;

@Service
public class ParkingService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ParkingService.class);
    private final ParkingTransactions transactions;
    private final ParkingSettings settings;
    public ParkingService(@Lazy ParkingTransactions transactions, ParkingSettings settings) {
        this.transactions=transactions; this.settings=settings;
    }
    public record Saved(boolean created, ParkingResponse response) {}
    public Saved save(UUID user,ParkingRequest request) {
        request=request.validated(settings);
        try {
            var result=transactions.save(user,request);
            return new Saved(result.created(),ParkingResponse.from(result));
        } catch (RuntimeException failure) {
            // Transaction proxy has completed rollback before known constraint translation.
            for (Throwable cause=failure;cause!=null;cause=cause.getCause()) {
                if (cause instanceof ParkingConfigurationException configuration) {
                    // No throwable argument: its cause chain may contain SQL, private inputs or provider bodies.
                    log.error("Parking configuration rejected: code={}",configuration.code());
                    throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
                }
                if (cause instanceof org.postgresql.util.PSQLException pg && pg.getServerErrorMessage()!=null) {
                    String constraint=pg.getServerErrorMessage().getConstraint();
                    if ("23514".equals(pg.getSQLState())) {
                        if ("ck_pe_adjust_200m".equals(constraint)) throw new BusinessException(ErrorCode.ADJUST_DISTANCE_EXCEEDED);
                        if ("ck_pe_no_auto_without_gps".equals(constraint)) throw new BusinessException(ErrorCode.AUTO_CONFIRM_NOT_ALLOWED);
                        if ("ck_pe_time_order".equals(constraint)) throw new BusinessException(ErrorCode.INVALID_TIME_ORDER);
                    }
                    if ("23503".equals(pg.getSQLState()) && "parking_events_vehicle_id_user_id_fkey".equals(constraint))
                        throw new BusinessException(ErrorCode.VEHICLE_NOT_FOUND);
                }
            }
            if (failure instanceof BusinessException business) throw business;
            // SQL/JDBC/HTTP failure causes can contain private coordinates. Do not chain them to the shared logger.
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
