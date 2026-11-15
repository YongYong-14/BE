package io.github.yongyong14.parking_server.parking;

import java.util.concurrent.Executor;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;

@Component
public class ParkingGeocodingListener {
    private static final Logger log=LoggerFactory.getLogger(ParkingGeocodingListener.class);
    private final Executor executor;
    private final ParkingGeocodingTransactions transactions;
    public ParkingGeocodingListener(@Qualifier("parkingGeocodingExecutor") Executor executor,
            @Lazy ParkingGeocodingTransactions transactions) { this.executor=executor; this.transactions=transactions; }
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void committed(ParkingTransactions.Created event) {
        try {
            executor.execute(()->{
                try { transactions.fillAddress(event); }
                catch (RuntimeException unavailable) { log.debug("Parking address task skipped or failed"); }
            });
        } catch (RuntimeException rejected) { log.warn("Parking address queue unavailable"); }
    }
}
