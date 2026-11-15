package io.github.yongyong14.parking_server.parking;

import java.util.*;

/** OTHER supplies the level-2 fine; it is never a facility geometry candidate. */
final class ParkingRuleContract {
    private ParkingRuleContract() {}

    static Number otherFine(List<Map<String,Object>> rules) {
        var byType = new HashMap<String,Map<String,Object>>();
        for (var rule : rules) {
            var type = (String) rule.get("zone_type");
            if (byType.put(type, rule) != null) invalid();
        }
        if (!byType.keySet().equals(Set.of("HYDRANT", "BUS_STOP", "OTHER"))) invalid();
        distance(byType.get("HYDRANT"), 5);
        distance(byType.get("BUS_STOP"), 10);
        var other = byType.get("OTHER");
        if (!"COVERS".equals(other.get("geometry_rule")) || other.get("legal_distance_m") != null) invalid();
        if (!(other.get("fine_amount_car") instanceof Number fine)
                || !Double.isFinite(fine.doubleValue()) || fine.doubleValue() < 0) invalid();
        return (Number) other.get("fine_amount_car");
    }

    private static void distance(Map<String,Object> rule, int meters) {
        if (!"DISTANCE".equals(rule.get("geometry_rule"))
                || !(rule.get("legal_distance_m") instanceof Number value) || value.doubleValue() != meters) invalid();
    }

    private static void invalid() {
        throw new ParkingConfigurationException(ParkingConfigurationException.Code.FACILITY_RULE_CONTRACT_MISMATCH);
    }
}
