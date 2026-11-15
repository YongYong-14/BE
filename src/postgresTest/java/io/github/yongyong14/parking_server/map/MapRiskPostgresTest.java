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
        assertEquals(3,repo.facilities(b,"yongin-2026-11-v1").size());
        facilityRadiusBoundaries(jdbc,api,repo);
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
        // The original viewport is exactly 2km; facilities may extend beyond it.
        var limitBounds=new MapBounds(37.32,127.09,edge,127.09);
        var limitResponse=service.get(limitBounds,null);
        assertTrue(limitResponse.facilityZones().stream().anyMatch(f->f.location().lng()<limitBounds.minLng()));
        assertEquals(repo.predictions(limitBounds,v,limitResponse.slot()).size(),limitResponse.cells().size());
        assertFalse(api.sql("SELECT has_table_privilege(current_user,'report_aggregates','SELECT')").query(Boolean.class).single());
        jdbc.sql("UPDATE prediction_versions SET active=false WHERE active").update();
        var noVersion=service.get(b,null);
        assertTrue(noVersion.cells().isEmpty()); assertNull(noVersion.dataPeriod());
        assertEquals(new MapRiskResponse.Slot(6,4),noVersion.slot());
    }
    private void facilityRadiusBoundaries(JdbcClient jdbc,JdbcClient api,MapRiskRepository repo) {
        String rules="yongin-2026-11-v1";
        // Far from the baseline fixtures. All centers are west of the original viewport.
        for(var viewport:java.util.List.of(
                new MapBounds(0,0,0,0),
                new MapBounds(0,0,0,.001),
                new MapBounds(0,0,.001,0),
                new MapBounds(0,0,.001,.001))) {
            for(String type:java.util.List.of("HYDRANT","BUS_STOP")) {
                double radius=jdbc.sql("SELECT legal_distance_m FROM zone_rules WHERE rule_set=:rules AND zone_type=:type")
                        .param("rules",rules).param("type",type).query(Double.class).single();
                // Include half radius, exact tangency, and 1mm inside; exclude 1mm outside.
                for(double distance:new double[]{radius/2,radius,radius-.001,radius+.001}) {
                    var id=java.util.UUID.randomUUID();
                    jdbc.sql("""
                        INSERT INTO facilities(id,import_id,source_record_key,zone_type,spatial_data)
                        VALUES (:id,'00000000-0000-0000-0000-000000000001',:key,:type,
                          ST_Project(ST_SetSRID(ST_MakePoint(:lng,:lat),4326)::geography,:distance,-pi()/2))
                        """).param("id",id).param("key",id.toString()).param("type",type)
                            .param("lng",viewport.minLng()).param("lat",viewport.minLat()).param("distance",distance).update();
                    try {
                        assertTrue(jdbc.sql("SELECT ST_X(spatial_data::geometry)<:lng FROM facilities WHERE id=:id")
                                .param("lng",viewport.minLng()).param("id",id).query(Boolean.class).single());
                        double actual=jdbc.sql("SELECT ST_Distance(spatial_data,("+MapRiskRepository.VIEWPORT+")::geography) FROM facilities WHERE id=:id")
                                .param("minLat",viewport.minLat()).param("maxLat",viewport.maxLat())
                                .param("minLng",viewport.minLng()).param("maxLng",viewport.maxLng()).param("id",id)
                                .query(Double.class).single();
                        assertEquals(distance,actual,.00001,"Distance in spheroid meters");
                        var zones=repo.facilities(viewport,rules);
                        assertEquals(distance<=radius ? 1 : 0,zones.size(),type+" distance="+distance+" viewport="+viewport);
                        if(!zones.isEmpty()) {
                            assertEquals(type,zones.getFirst().zoneType());
                            assertEquals(radius,zones.getFirst().radiusM());
                            assertTrue(zones.getFirst().location().lng()<viewport.minLng());
                        }
                    } finally { jdbc.sql("DELETE FROM facilities WHERE id=:id").param("id",id).update(); }
                }
            }
        }
        // EXPLAIN with enough synthetic rows to exercise the GiST radius prefilter.
        jdbc.sql("""
            INSERT INTO facilities(id,import_id,source_record_key,zone_type,spatial_data)
            SELECT gen_random_uuid(),'00000000-0000-0000-0000-000000000001','index-'||n,'HYDRANT',
              ST_SetSRID(ST_MakePoint(126+n*.00001,36+n*.00001),4326)
            FROM generate_series(1,10000) n
            """).update();
        jdbc.sql("ANALYZE facilities").update();
        String plan=String.join("\n",api.sql("EXPLAIN "+MapRiskRepository.FACILITIES_SQL)
                .param("minLat",37.4).param("maxLat",37.401).param("minLng",127.2).param("maxLng",127.201)
                .param("rules",rules).query(String.class).list());
        assertTrue(plan.contains("ix_facilities_spatial"),plan);
        assertTrue(plan.contains("Index Cond:") && plan.contains("&&") && plan.contains("_st_expand"),plan);
        jdbc.sql("DELETE FROM facilities WHERE source_record_key LIKE 'index-%'").update();
    }
    private static String required(String key) {
        String value=System.getenv(key); if(value==null||value.isBlank()) throw new IllegalStateException("Use Verify-MapRiskZones.ps1"); return value;
    }
}
