package io.github.yongyong14.parking_server.parking;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ParkingDataConfiguration {
    @Bean("parkingGeocodingExecutor")
    org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor parkingGeocodingExecutor() {
        var executor=new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1); executor.setMaxPoolSize(2); executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("parking-address-");
        return executor;
    }
    @Bean
    @ConditionalOnMissingBean(GridResolver.class)
    GridResolver postgisGridResolver() {
        return new PostgisGridResolver();
    }
    static class DataContractUnavailable extends RuntimeException {
        DataContractUnavailable(String code) { super(code); }
    }
}
