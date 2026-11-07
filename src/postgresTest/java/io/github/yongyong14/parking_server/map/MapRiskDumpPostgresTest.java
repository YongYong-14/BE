package io.github.yongyong14.parking_server.map;

import java.nio.file.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class MapRiskDumpPostgresTest {
    @Test void migrateFreshReferenceDatabase() {
        String url=required("AUTH_TEST_JDBC_URL"), password=required("AUTH_TEST_PASSWORD");
        if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:\\d+/auth_verify")) throw new IllegalStateException("Isolated DB required");
        for(String role:java.util.List.of("parking_api","parking_migrator")) {
            var db=JdbcClient.create(new DriverManagerDataSource(url,role,password));
            assertEquals("auth_verify:"+role+":"+required("AUTH_TEST_IDENTITY"),db.sql(
                    "SELECT current_database()||':'||current_user||':'||current_setting('verification.identity',true)").query(String.class).single());
        }
        var flyway=org.flywaydb.core.Flyway.configure().dataSource(url,"parking_migrator",password)
                .defaultSchema("flyway").schemas("flyway").placeholderReplacement(false).cleanDisabled(true).load();
        flyway.migrate();
        assertEquals("6",flyway.info().current().getVersion().toString());
        assertTrue(JdbcClient.create(new DriverManagerDataSource(url,"parking_api",password)).sql(
                "SELECT has_table_privilege(current_user,'report_aggregates','SELECT')").query(Boolean.class).single());
    }
    @Test void referenceDumpMatchesServingCountsAndApplicationPrivileges() throws Exception {
        String url=required("AUTH_TEST_JDBC_URL");
        if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:\\d+/auth_verify")) throw new IllegalStateException("Isolated DB required");
        String password=required("AUTH_TEST_PASSWORD");
        var owner=new DriverManagerDataSource(url,"parking_migrator",password);
        var jdbc=JdbcClient.create(owner);
        assertEquals("auth_verify:parking_migrator:"+required("AUTH_TEST_IDENTITY"),jdbc.sql(
                "SELECT current_database()||':'||current_user||':'||current_setting('verification.identity',true)").query(String.class).single());
        // Assert the allowlisted restore never brought in user data.
        for(String table:new String[]{"users","vehicles","parking_events","risk_assessments","location_access_logs","refresh_tokens"})
            assertEquals(0L,jdbc.sql("SELECT count(*) FROM public."+table).query(Long.class).single());
        try(var connection=owner.getConnection();var statement=connection.createStatement()) {
            statement.execute(Files.readString(Path.of("docs/rules_v1.sql")));
        }
        String rules="yongin-2026-11-v1";
        assertEquals(3,jdbc.sql("SELECT count(*) FROM zone_rules WHERE rule_set=:rules").param("rules",rules).query(Integer.class).single());
        assertEquals(5.0,jdbc.sql("SELECT legal_distance_m FROM zone_rules WHERE rule_set=:rules AND zone_type='HYDRANT'").param("rules",rules).query(Double.class).single());
        assertEquals(10.0,jdbc.sql("SELECT legal_distance_m FROM zone_rules WHERE rule_set=:rules AND zone_type='BUS_STOP'").param("rules",rules).query(Double.class).single());
        jdbc.sql("UPDATE zone_rule_sets SET published_at=now() WHERE rule_set=:rules").param("rules",rules).update();
        var api=JdbcClient.create(new DriverManagerDataSource(url,"parking_api",password));
        var repo=new MapRiskRepository(api);
        var v=repo.active().orElseThrow(); repo.requireStatistics(v); repo.requireRules(rules,Instant.now().plusSeconds(1));
        var point=jdbc.sql("""
            SELECT ST_Y(f.spatial_data::geometry) lat,ST_X(f.spatial_data::geometry) lng
            FROM facilities f JOIN data_imports i ON i.id=f.import_id AND i.active
            WHERE f.zone_type='HYDRANT' ORDER BY f.id LIMIT 1
            """).query((rs,n)->new MapRiskResponse.Location(rs.getDouble("lat"),rs.getDouble("lng"))).single();
        var facilityBounds=new MapBounds(point.lat()-.002,point.lng()-.002,point.lat()+.002,point.lng()+.002);
        var facilities=repo.facilities(facilityBounds,rules);
        assertFalse(facilities.isEmpty());
        assertTrue(facilities.stream().allMatch(f->f.zoneType().equals("HYDRANT")&&f.radiusM()==5
                || f.zoneType().equals("BUS_STOP")&&f.radiusM()==10));
        var location=jdbc.sql("""
            SELECT ST_Y(ST_Centroid(g.boundary)) lat,ST_X(ST_Centroid(g.boundary)) lng
            FROM risk_predictions p JOIN spatial_grids g ON g.id=p.grid_id
            WHERE p.version_id=:version AND p.weekday=1 AND p.time_slot=0
              AND p.top_percent<=:threshold ORDER BY p.top_percent,g.id LIMIT 1
            """).param("version",v.id()).param("threshold",v.threshold())
                .query((rs,n)->new MapRiskResponse.Location(rs.getDouble("lat"),rs.getDouble("lng"))).single();
        var bounds=new MapBounds(location.lat()-.003,location.lng()-.003,location.lat()+.003,location.lng()+.003);
        assertTrue(repo.withinLimit(bounds));
        var predictions=repo.predictions(bounds,v,new MapRiskResponse.Slot(1,0));
        assertFalse(predictions.isEmpty());
        int compared=0;
        for(var p:predictions) {
            assertNotNull(p.topPercent());
            if(p.topPercent()>v.threshold()) continue;
            long expected=jdbc.sql("""
                SELECT coalesce(sum(report_count),0)::bigint FROM report_aggregates
                WHERE import_id=:source AND grid_id=:grid AND period_start=:start AND period_end=:end
                """).param("source",v.source()).param("grid",p.gridId()).param("start",v.start()).param("end",v.end())
                    .query(Long.class).single();
            assertEquals(expected,p.reports()==null?0:p.reports(),"Serving report counts must match model aggregate lineage");
            compared++;
        }
        assertTrue(compared>0);
        var service=new MapRiskService(repo,Clock.fixed(Instant.now().plusSeconds(1),ZoneOffset.UTC),rules);
        var at=OffsetDateTime.parse("2026-11-02T00:00:00+09:00");
        var response=service.get(bounds,at);
        assertEquals(new MapRiskResponse.Slot(1,0),response.slot());
        assertEquals(compared,response.cells().size());
        assertEquals(new MapRiskResponse.DataPeriod(v.start(),v.end()),response.dataPeriod());
        assertEquals(response,service.get(bounds,at.withOffsetSameInstant(ZoneOffset.UTC)));
        verifyParkingAndPeakOnReferenceData(url,password,rules,v,jdbc,api);
        explainMaximumViewport(api,repo,v,location,rules);
        assertTrue(api.sql("SELECT has_table_privilege(current_user,'report_aggregates','SELECT')").query(Boolean.class).single());
        System.out.println("DUMP_MAP_VERIFIED facilities="+facilities.size()+" comparedRiskCells="+compared
                +" period="+v.start()+"~"+v.end());
    }
    private void verifyParkingAndPeakOnReferenceData(String url,String password,String rules,MapRiskRepository.Version v,
            JdbcClient owner,JdbcClient api) {
        boolean holiday=api.sql("SELECT EXISTS(SELECT 1 FROM holidays WHERE holiday_date=DATE '2026-11-07')").query(Boolean.class).single();
        int weekday=holiday && "AS_SUNDAY".equals(v.policy())?7:6;
        var position=api.sql("""
            SELECT ST_Y(ST_Centroid(g.boundary)) lat,ST_X(ST_Centroid(g.boundary)) lng
            FROM prediction_target_grids t JOIN spatial_grids g ON g.id=t.grid_id
            WHERE t.version_id=:version AND NOT EXISTS (
              SELECT 1 FROM facilities f JOIN data_imports i ON i.id=f.import_id AND i.active
              JOIN zone_rules r ON r.zone_type=f.zone_type AND r.rule_set=:rules
              WHERE f.zone_type IN ('HYDRANT','BUS_STOP')
                AND ST_DWithin(f.spatial_data,ST_Centroid(g.boundary)::geography,r.legal_distance_m))
            AND EXISTS (SELECT 1 FROM report_aggregates a WHERE a.grid_id=g.id AND a.import_id=:source
              AND a.weekday=:weekday AND a.period_start=:start AND a.period_end=:end)
            ORDER BY g.id LIMIT 1
            """).param("version",v.id()).param("rules",rules)
                .param("source",v.source()).param("weekday",weekday).param("start",v.start()).param("end",v.end())
                .query((rs,n)->new MapRiskResponse.Location(rs.getDouble("lat"),rs.getDouble("lng"))).single();
        var user=java.util.UUID.randomUUID(); var vehicle=java.util.UUID.randomUUID();
        owner.sql("INSERT INTO users(id,kakao_id) VALUES (:id,:kakao)").param("id",user).param("kakao",Math.abs(new java.util.Random().nextLong())).update();
        try {
            owner.sql("INSERT INTO vehicles(id,user_id,name,bluetooth_address) VALUES (:v,:u,'reference','AA:BB:CC:DD:EE:FA')")
                    .param("v",vehicle).param("u",user).update();
            owner.sql("INSERT INTO notification_settings(user_id) VALUES (:u)").param("u",user).update();
            owner.sql("INSERT INTO user_agreements(user_id,agreement_type,version,action) VALUES (:u,'LOCATION_SERVICE','reference-current','AGREE')").param("u",user).update();
            var source=new DriverManagerDataSource(url,"parking_api",password);
            var clock=Clock.fixed(Instant.parse("2026-11-07T10:05:41Z"),ZoneOffset.UTC);
            var settings=new io.github.yongyong14.parking_server.parking.ParkingSettings(rules,java.util.List.of("HYDRANT","BUS_STOP"),Duration.ofMinutes(2),Duration.ofMinutes(10),10,50);
            var store=new io.github.yongyong14.parking_server.parking.ParkingStore(source);
            var transactions=new io.github.yongyong14.parking_server.parking.ParkingTransactions(store,
                    new io.github.yongyong14.parking_server.parking.ParkingAssessmentEngine(store,settings,new io.github.yongyong14.parking_server.parking.PostgisGridResolver()),
                    settings,new io.github.yongyong14.parking_server.global.security.AgreementPolicy("reference-current"),clock);
            var parking=new io.github.yongyong14.parking_server.parking.ParkingService(transactions,settings);
            var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
            var detected=OffsetDateTime.parse("2026-11-07T19:05:12+09:00");
            var input=new io.github.yongyong14.parking_server.parking.ParkingRequest(java.util.UUID.randomUUID(),vehicle,detected,detected.plusSeconds(28),
                    io.github.yongyong14.parking_server.parking.ParkingRequest.Method.CONFIRMED,null,null,
                    new io.github.yongyong14.parking_server.parking.ParkingRequest.Point(java.math.BigDecimal.valueOf(position.lat()),java.math.BigDecimal.valueOf(position.lng())),0);
            var saved=tx.execute(s->parking.save(user,input).response());
            assertNotNull(saved);
            assertEquals("SUCCEEDED",saved.assessment().status());
            var row=api.sql("SELECT prediction_version_id,source_import_id,grid_id,weekday,time_slot FROM risk_assessments WHERE parking_event_id=:id")
                    .param("id",saved.parkingEventId()).query().singleRow();
            assertEquals(v.id(),row.get("prediction_version_id")); assertEquals(v.source(),row.get("source_import_id"));
            double raw=api.sql("SELECT top_percent FROM risk_predictions WHERE version_id=:v AND grid_id=:g AND weekday=:w AND time_slot=:s")
                    .param("v",v.id()).param("g",row.get("grid_id")).param("w",row.get("weekday")).param("s",row.get("time_slot")).query(Double.class).single();
            assertEquals(raw,saved.assessment().stats().topPercent());
            assertEquals(raw<=v.threshold()?2:3,saved.assessment().level());
            var detail=tx.execute(s->new io.github.yongyong14.parking_server.parking.ParkingQueryTransactions(store).detail(user,saved.parkingEventId()));
            var counts=api.sql("SELECT time_slot,report_count FROM report_aggregates WHERE import_id=:source AND grid_id=:grid AND weekday=:weekday AND period_start=:start AND period_end=:end ORDER BY time_slot")
                    .param("source",v.source()).param("grid",row.get("grid_id")).param("weekday",row.get("weekday"))
                    .param("start",v.start()).param("end",v.end()).query().listOfRows();
            if(counts.isEmpty()) assertNull(detail.assessment().stats().peak());
            else {
                var expected=new java.util.ArrayList<>(java.util.Collections.nCopies(8,0L));
                for(var count:counts) expected.set(((Number)count.get("time_slot")).intValue(),((Number)count.get("report_count")).longValue());
                assertEquals(expected,detail.assessment().stats().peak().slotCounts());
            }
            var mapped=new MapRiskService(new MapRiskRepository(api),clock,rules).get(new MapBounds(position.lat(),position.lng(),position.lat(),position.lng()),detected);
            assertEquals(raw<=v.threshold(),!mapped.cells().isEmpty());
            assertEquals(saved.assessment(),tx.execute(s->parking.save(user,input).response().assessment()));
            System.out.println("REFERENCE_PARKING_PEAK_MAP_VERIFIED");
        } finally { owner.sql("DELETE FROM users WHERE id=:id").param("id",user).update(); }
    }
    private void explainMaximumViewport(JdbcClient api,MapRiskRepository repo,MapRiskRepository.Version v,
            MapRiskResponse.Location center,String rules) throws Exception {
        // Derive a full 2km-by-2km rectangle with the actual API distance predicate, not degree guesses.
        double minLat=center.lat()-.009, minLng=center.lng()-.011;
        double low=minLat, high=minLat+.05;
        for(int i=0;i<55;i++) {
            double mid=(low+high)/2;
            if(repo.withinLimit(new MapBounds(minLat,minLng,mid,minLng))) low=mid; else high=mid;
        }
        double maxLat=low; low=minLng; high=minLng+.05;
        for(int i=0;i<55;i++) {
            double mid=(low+high)/2;
            if(repo.withinLimit(new MapBounds(minLat,minLng,maxLat,mid))) low=mid; else high=mid;
        }
        var b=new MapBounds(minLat,minLng,maxLat,low);
        assertTrue(repo.withinLimit(b));
        assertFalse(repo.withinLimit(new MapBounds(minLat,minLng,maxLat,low+1e-9)));
        assertFalse(repo.withinLimit(new MapBounds(minLat,minLng,maxLat+1e-9,low)));
        var params=new java.util.HashMap<String,Object>();
        params.put("minLat",b.minLat());params.put("minLng",b.minLng());
        params.put("maxLat",b.maxLat());params.put("maxLng",b.maxLng());
        params.put("rules",rules);params.put("version",v.id());params.put("source",v.source());
        params.put("start",v.start());params.put("end",v.end());params.put("threshold",v.threshold());
        params.put("srid",v.srid());params.put("size",v.size());params.put("weekday",1);params.put("slot",0);
        Path directory=Path.of("build/map-verification"); Files.createDirectories(directory);
        // Execute the exact production statements as parking_api. Do not log literal geometry or request values.
        for(var entry:java.util.Map.of("facilities",MapRiskRepository.FACILITIES_SQL,
                "predictions",MapRiskRepository.PREDICTIONS_SQL).entrySet()) {
            String plan=api.sql("EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) "+entry.getValue())
                    .params(params).query(String.class).single();
            String sanitized=plan.replaceAll("'[^']*'","'[literal]'");
            Files.writeString(directory.resolve("max-2km-"+entry.getKey()+"-plan.json"),sanitized);
            var tree=new tools.jackson.databind.ObjectMapper().readTree(plan).get(0);
            System.out.println("MAX_2KM_PLAN query="+entry.getKey()+" executionMs="+tree.get("Execution Time")
                    +" planningMs="+tree.get("Planning Time")+" rows="+tree.get("Plan").get("Actual Rows"));
        }
    }
    private static String required(String key) {
        String value=System.getenv(key); if(value==null||value.isBlank()) throw new IllegalStateException("Use Verify-MapRiskZones.ps1 -ReferenceDump"); return value;
    }
}
