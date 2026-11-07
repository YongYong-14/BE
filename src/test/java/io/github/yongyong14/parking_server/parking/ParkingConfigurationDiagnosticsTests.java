package io.github.yongyong14.parking_server.parking;

import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ParkingConfigurationDiagnosticsTests {
    final ParkingSettings settings=new ParkingSettings("test-rule",List.of(),Duration.ofMinutes(2),Duration.ofMinutes(10),10,50);
    ParkingStore store(NamedParameterJdbcTemplate jdbc) {
        var store=new ParkingStore(mock(DataSource.class));
        ReflectionTestUtils.setField(store,"jdbc",jdbc);
        return store;
    }
    @Test void missingOrUnpublishedRuleSetHasFixedDiagnostic() {
        var jdbc=mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForList(anyString(),anyMap())).thenReturn(List.of());
        var engine=new ParkingAssessmentEngine(store(jdbc),settings,new PostgisGridResolver());
        assertThatThrownBy(engine::snapshot).isInstanceOfSatisfying(ParkingConfigurationException.class,
                error->assertThat(error.code()).isEqualTo(ParkingConfigurationException.Code.PUBLIC_RULE_SET_UNAVAILABLE));
    }
    @Test void unfrozenSelectedVersionHasFixedDiagnosticWithoutBypassingDatabaseGuards() {
        var jdbc=mock(NamedParameterJdbcTemplate.class);
        var row=new HashMap<String,Object>(); row.put("id",UUID.randomUUID()); row.put("frozen_at",null);
        when(jdbc.queryForList(anyString(),anyMap())).thenReturn(List.of(row));
        var engine=new ParkingAssessmentEngine(store(jdbc),settings,new PostgisGridResolver());
        assertThatThrownBy(engine::snapshot).isInstanceOfSatisfying(ParkingConfigurationException.class,
                error->assertThat(error.code()).isEqualTo(ParkingConfigurationException.Code.MODEL_VERSION_NOT_FROZEN));
    }
    @Test void invalidFacilityRuleContractHasFixedDiagnostic() {
        var jdbc=mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForList(anyString(),anyMap())).thenAnswer(invocation->
                invocation.<String>getArgument(0).contains("SELECT zone_type,geometry_rule,legal_distance_m")
                        ? List.of(Map.of("zone_type","HYDRANT","geometry_rule","DISTANCE","legal_distance_m",99),
                                  Map.of("zone_type","BUS_STOP","geometry_rule","DISTANCE","legal_distance_m",10)) : List.of());
        when(jdbc.queryForObject(anyString(),anyMap(),eq(Boolean.class))).thenReturn(false);
        var store=store(jdbc);
        var engine=new ParkingAssessmentEngine(store,settings,new PostgisGridResolver()) {
            @Override Snapshot snapshot() { return new Snapshot("test-rule",null,null,List.of()); }
        };
        var time=OffsetDateTime.parse("2026-11-07T19:00:00+09:00");
        var request=new ParkingRequest(UUID.randomUUID(),UUID.randomUUID(),time,time,ParkingRequest.Method.CONFIRMED,null,null,
                new ParkingRequest.Point(java.math.BigDecimal.ZERO,java.math.BigDecimal.ZERO),0);
        assertThatThrownBy(()->engine.evaluate(UUID.randomUUID(),UUID.randomUUID(),request,1,time.toInstant()))
                .isInstanceOfSatisfying(ParkingConfigurationException.class,
                        error->assertThat(error.code()).isEqualTo(ParkingConfigurationException.Code.FACILITY_RULE_CONTRACT_MISMATCH));
    }
}
