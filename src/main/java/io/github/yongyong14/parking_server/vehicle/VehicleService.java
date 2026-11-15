package io.github.yongyong14.parking_server.vehicle;

import io.github.yongyong14.parking_server.global.exception.*;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

@Service
public class VehicleService {
    private final VehicleTransactions transactions;
    public VehicleService(@Lazy VehicleTransactions transactions) { this.transactions = transactions; }
    public VehicleResponse get(UUID userId) { return transactions.get(userId); }
    public VehicleResponse put(UUID userId, VehicleRequest request) {
        request.validateAddress();
        // The transactional proxy has rolled back before translation; never query an aborted transaction.
        try { return transactions.put(userId, request); }
        catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException constraint && "23505".equals(constraint.getSQLState())
                        && "uq_vehicles_active_user".equals(constraint.getConstraintName()))
                    throw new BusinessException(ErrorCode.VEHICLE_CONFLICT);
                if (cause instanceof jakarta.persistence.OptimisticLockException || cause instanceof OptimisticLockingFailureException)
                    throw new BusinessException(ErrorCode.VEHICLE_CONFLICT);
            }
            // Avoid leaking SQL bind values (including addresses) through the common exception logger.
            if (failure instanceof org.springframework.dao.DataAccessException || failure instanceof jakarta.persistence.PersistenceException)
                throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
            throw failure;
        }
    }
}
