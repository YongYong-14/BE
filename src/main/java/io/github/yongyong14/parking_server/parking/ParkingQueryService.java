package io.github.yongyong14.parking_server.parking;

import java.util.*;
import java.util.function.Supplier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import io.github.yongyong14.parking_server.global.exception.*;

@Service
public class ParkingQueryService {
    private final ParkingQueryTransactions transactions;
    private final ParkingDeliveryTransactions delivery;
    public ParkingQueryService(@Lazy ParkingQueryTransactions transactions,@Lazy ParkingDeliveryTransactions delivery) {
        this.transactions=transactions; this.delivery=delivery;
    }
    public void delivery(UUID user,UUID id,ParkingDeliveryRequest request) {
        var normalized=request.validated();
        safely(()-> { delivery.report(user,id,normalized); return null; });
    }
    public ParkingQueryResponse.Latest latest(UUID user) { return safely(()->transactions.latest(user)); }
    public ParkingQueryResponse.Detail detail(UUID user,UUID id) { return safely(()->transactions.detail(user,id)); }
    public ParkingQueryResponse.Items list(UUID user,String level,int limit) {
        if (!Set.of("ALL","1","2").contains(level) || limit<1 || limit>100) throw new BusinessException(ErrorCode.INVALID_INPUT);
        return safely(()->transactions.list(user,level,limit));
    }
    private <T> T safely(Supplier<T> action) {
        try { return action.get(); }
        catch (BusinessException e) { throw e; }
        catch (RuntimeException e) {
            // JDBC exception chains can include coordinates and bindings. Match the POST boundary.
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
