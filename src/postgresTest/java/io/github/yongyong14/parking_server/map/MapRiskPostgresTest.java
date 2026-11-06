package io.github.yongyong14.parking_server.map;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class MapRiskPostgresTest {
    @Test void realPostgisQueriesAndApplicationGrants() throws Exception {
        String url=required("AUTH_TEST_JDBC_URL");
        if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:\\d+/auth_verify")) throw new IllegalStateException("Isolated DB required");
        String password=required("AUTH_TEST_PASSWORD");
        var owner=new DriverManagerDataSource(url,"parking_migrator",password);
        var jdbc=JdbcClient.create(owner);
        assertEquals("auth_verify:parking_migrator:"+required("AUTH_TEST_IDENTITY"),jdbc.sql(
                "SELECT current_database()||':'||current_user||':'||current_setting('verification.identity',true)").query(String.class).single());
        try(var connection=owner.getConnection();var statement=connection.createStatement()) {
            for(String file:new String[]{"V2__erd_v5_schema.sql","V3__erd_v5_guards_and_indexes.sql","V4__erd_v5_grants.sql","V5__parking_lots_hours_and_fee.sql"}) {
                connection.setAutoCommit(false);
                statement.execute(new ClassPathResource("db/migration/"+file).getContentAsString(StandardCharsets.UTF_8));
                connection.commit();
            }
            statement.execute("INSERT INTO zone_types VALUES ('HYDRANT','hydrant',true),('BUS_STOP','bus',true),('OTHER','other',false)");
            statement.execute(Files.readString(Path.of("docs/rules_v1.sql")));
        }
        var api=JdbcClient.create(new DriverManagerDataSource(url,"parking_api",password));
        var repo=new MapRiskRepository(api);
        var b=new MapBounds(37.32,127.09,37.321,127.091);
        var now=Instant.parse("2026-11-07T00:00:00Z");
        assertTrue(repo.withinLimit(b));
        assertFalse(repo.withinLimit(new MapBounds(37.32,127.09,37.35,127.091)));
        assertFalse(repo.withinLimit(new MapBounds(0,-180,0,180)));
        // Exact 2km meridian distance computed by the same WGS84 spheroid; test either side.
        double edge=jdbc.sql("SELECT ST_Y(ST_Project(ST_SetSRID(ST_MakePoint(127.09,37.32),4326)::geography,2000,0)::geometry)").query(Double.class).single();
        assertTrue(repo.withinLimit(new MapBounds(37.32,127.09,edge,127.09)));
        assertTrue(repo.withinLimit(new MapBounds(37.32,127.09,edge-0.000000001,127.09)));
        assertFalse(repo.withinLimit(new MapBounds(37.32,127.09,edge+0.000000001,127.09)));
        assertThrows(Exception.class,()->repo.requireRules("missing",now));
        assertThrows(Exception.class,()->repo.requireRules("yongin-2026-11-v1",now));
        assertEquals(3,jdbc.sql("SELECT count(*) FROM zone_rules WHERE rule_set='yongin-2026-11-v1'").query(Integer.class).single());
        assertEquals(5.0,jdbc.sql("SELECT legal_distance_m FROM zone_rules WHERE zone_type='HYDRANT'").query(Double.class).single());
        jdbc.sql("UPDATE zone_rule_sets SET published_at='2026-11-01T00:00:00Z' WHERE rule_set='yongin-2026-11-v1'").update();
        repo.requireRules("yongin-2026-11-v1",now);
        try(var c=owner.getConnection();var s=c.createStatement()) { s.execute(Files.readString(Path.of("src/postgresTest/resources/map-fixture.sql"))); }
        assertEquals(2,repo.facilities(b,"yongin-2026-11-v1").size());
        assertTrue(repo.facilities(b,"yongin-2026-11-v1").stream().allMatch(f->
                f.zoneType().equals("HYDRANT")&&f.radiusM()==5 || f.zoneType().equals("BUS_STOP")&&f.radiusM()==10));
        var v=repo.active().orElseThrow(); repo.requireStatistics(v);
        // Same definition for both queries: point, horizontal line, vertical line, rectangle.
        int shapeIndex=0;
        for(var viewport:java.util.List.of(
                new MapBounds(37.32,127.09,37.32,127.09),
                new MapBounds(37.32,127.09,37.32,127.091),
                new MapBounds(37.32,127.09,37.321,127.09),b)) {
            assertTrue(repo.withinLimit(viewport));
            assertEquals(java.util.List.of("POINT","LINESTRING","LINESTRING","POLYGON").get(shapeIndex++),
                    api.sql("SELECT GeometryType("+MapRiskRepository.VIEWPORT+")")
                            .param("minLat",viewport.minLat()).param("maxLat",viewport.maxLat())
                            .param("minLng",viewport.minLng()).param("maxLng",viewport.maxLng())
                            .query(String.class).single());
            assertTrue(repo.facilities(viewport,"yongin-2026-11-v1").stream()
                    .anyMatch(f->f.zoneType().equals("HYDRANT")));
            long expected=api.sql("""
                WITH viewport AS (SELECT %s AS area)
                SELECT count(*) FROM spatial_grids g JOIN prediction_target_grids t ON t.grid_id=g.id,
                  viewport v WHERE t.version_id=:version AND ST_Intersects(g.boundary,v.area)
                """.formatted(MapRiskRepository.VIEWPORT))
                    .param("minLat",viewport.minLat()).param("maxLat",viewport.maxLat())
                    .param("minLng",viewport.minLng()).param("maxLng",viewport.maxLng()).param("version",v.id())
                    .query(Long.class).single();
            assertEquals(expected,repo.predictions(viewport,v,new MapRiskResponse.Slot(7,1)).size());
        }
        // A point on the actual grid corner includes the boundary-touching target cell.
        var corner=api.sql("SELECT ST_Y(ST_PointN(ST_ExteriorRing(boundary),1)) lat,ST_X(ST_PointN(ST_ExteriorRing(boundary),1)) lng FROM spatial_grids WHERE id=1")
                .query((rs,n)->new MapRiskResponse.Location(rs.getDouble("lat"),rs.getDouble("lng"))).single();
        assertEquals(1,repo.predictions(new MapBounds(corner.lat(),corner.lng(),corner.lat(),corner.lng()),v,
                new MapRiskResponse.Slot(7,1)).size());
        assertTrue(repo.holiday(LocalDate.of(2026,11,7)));
        var cells=repo.predictions(b,v,new MapRiskResponse.Slot(7,1));
        assertEquals(1,cells.size()); assertEquals(12L,cells.getFirst().reports());
        assertEquals(v.threshold(),cells.getFirst().topPercent());
        var service=new MapRiskService(repo,Clock.fixed(now.plusSeconds(3*3600),ZoneOffset.UTC),"yongin-2026-11-v1");
        var response=service.get(b,null);
        assertEquals(new MapRiskResponse.Slot(7,4),response.slot());
        assertEquals(new MapRiskResponse.DataPeriod(v.start(),v.end()),response.dataPeriod());
        assertEquals(12,response.cells().getFirst().reportCount());
        assertFalse(api.sql("SELECT has_table_privilege(current_user,'report_aggregates','SELECT')").query(Boolean.class).single());
        jdbc.sql("UPDATE prediction_versions SET active=false WHERE active").update();
        var noVersion=service.get(b,null);
        assertTrue(noVersion.cells().isEmpty()); assertNull(noVersion.dataPeriod());
        assertEquals(new MapRiskResponse.Slot(6,4),noVersion.slot());
    }
    private static String required(String key) {
        String value=System.getenv(key); if(value==null||value.isBlank()) throw new IllegalStateException("Use Verify-MapRiskZones.ps1"); return value;
    }
}
