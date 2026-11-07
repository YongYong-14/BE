package io.github.yongyong14.parking_server.parking;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ParkingRuleContractTests {
    List<Map<String,Object>> rules() {
        var other=new HashMap<String,Object>();
        other.put("zone_type","OTHER"); other.put("geometry_rule","COVERS");
        other.put("legal_distance_m",null); other.put("fine_amount_car",47321);
        return new ArrayList<>(List.of(
                Map.of("zone_type","HYDRANT","geometry_rule","DISTANCE","legal_distance_m",5),
                Map.of("zone_type","BUS_STOP","geometry_rule","DISTANCE","legal_distance_m",10),other));
    }
    @Test void acceptsThreeRulesAndReadsTheActualOtherFineWithoutAnAmountDefault() {
        var rows=rules(); Collections.reverse(rows);
        assertThat(ParkingRuleContract.otherFine(rows).intValue()).isEqualTo(47321);
    }
    @Test void missingOrDuplicateOtherIsRejected() {
        var missing=rules(); missing.removeLast(); invalid(missing);
        var duplicate=rules(); duplicate.add(duplicate.getLast()); invalid(duplicate);
    }
    @Test void rejectsWrongOtherGeometryDistanceOrMissingInvalidFine() {
        for (var change:List.of(Map.entry("geometry_rule",(Object)"DISTANCE"),Map.entry("legal_distance_m",(Object)0),
                Map.entry("fine_amount_car",(Object)(-1)),Map.entry("fine_amount_car",(Object)Double.NaN))) {
            var rows=rules(); rows.getLast().put(change.getKey(),change.getValue()); invalid(rows);
        }
        var rows=rules(); rows.getLast().put("fine_amount_car",null); invalid(rows);
    }
    @Test void rejectsMissingDuplicateOrInvalidFacilityDistance() {
        var rows=rules(); rows.removeFirst(); invalid(rows);
        rows=rules(); rows.add(rows.getFirst()); invalid(rows);
        rows=rules(); var wrong=new HashMap<>(rows.getFirst()); wrong.put("legal_distance_m",6); rows.set(0,wrong); invalid(rows);
    }
    void invalid(List<Map<String,Object>> rows) {
        assertThatThrownBy(()->ParkingRuleContract.otherFine(rows)).isInstanceOfSatisfying(ParkingConfigurationException.class,
                error->assertThat(error.code()).isEqualTo(ParkingConfigurationException.Code.FACILITY_RULE_CONTRACT_MISMATCH));
    }
}
