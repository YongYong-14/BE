package io.github.yongyong14.parking_server.parking;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Exact SQL contract from docs/schema/GRID_AND_TIME_RULES.md, not boundary polygon containment. */
public class PostgisGridResolver implements GridResolver {
    @Override public Grid resolve(NamedParameterJdbcTemplate jdbc, BigDecimal lat, BigDecimal lng, int srid, int cellSize) {
        if (srid != 5186 || cellSize != 50)
            throw new ParkingDataConfiguration.DataContractUnavailable("UNSUPPORTED_GRID_CONTRACT");
        var rows = jdbc.queryForList("""
                WITH p AS (SELECT ST_Transform(ST_SetSRID(ST_MakePoint(CAST(:lng AS float8),CAST(:lat AS float8)),4326),5186) AS point)
                SELECT g.id,g.in_model_area FROM public.spatial_grids g,p
                WHERE g.projected_srid=5186 AND g.cell_size_m=50
                  AND g.grid_x=floor(ST_X(p.point)/50)::int AND g.grid_y=floor(ST_Y(p.point)/50)::int
                """, Map.of("lat",lat,"lng",lng));
        return rows.isEmpty() ? new Grid(null,false)
                : new Grid(((Number)rows.getFirst().get("id")).longValue(),(Boolean)rows.getFirst().get("in_model_area"));
    }
}
