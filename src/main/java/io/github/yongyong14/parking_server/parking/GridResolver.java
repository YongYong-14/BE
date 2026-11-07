package io.github.yongyong14.parking_server.parking;

import java.math.BigDecimal;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Data-team contract. No guessed origin or polygon fallback when ingestion rules are unavailable. */
public interface GridResolver {
    Grid resolve(NamedParameterJdbcTemplate jdbc, BigDecimal lat, BigDecimal lng, int srid, int cellSize);
    record Grid(Long id, boolean inModelArea) {}
}
