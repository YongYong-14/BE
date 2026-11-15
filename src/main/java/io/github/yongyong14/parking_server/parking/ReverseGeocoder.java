package io.github.yongyong14.parking_server.parking;

import java.math.BigDecimal;
import java.util.Optional;

public interface ReverseGeocoder {
    boolean enabled();
    Optional<String> address(BigDecimal lat, BigDecimal lng) throws Exception;
}
