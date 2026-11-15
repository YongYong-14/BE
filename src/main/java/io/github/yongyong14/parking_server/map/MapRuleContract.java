package io.github.yongyong14.parking_server.map;

import java.util.*;
import io.github.yongyong14.parking_server.global.exception.*;

/** Matches #18 ParkingRuleContract; consolidate after that branch reaches dev. */
final class MapRuleContract {
    static void validate(List<Map<String,Object>> rules) {
        var byType=new HashMap<String,Map<String,Object>>();
        for(var rule:rules) if(byType.put((String)rule.get("zone_type"),rule)!=null) invalid();
        if(!byType.keySet().equals(Set.of("HYDRANT","BUS_STOP","OTHER"))) invalid();
        distance(byType.get("HYDRANT"),5);
        distance(byType.get("BUS_STOP"),10);
        var other=byType.get("OTHER");
        if(!"COVERS".equals(other.get("geometry_rule")) || other.get("legal_distance_m")!=null
                || !(other.get("fine_amount_car") instanceof Number fine)
                || !Double.isFinite(fine.doubleValue()) || fine.doubleValue()<0) invalid();
    }
    private static void distance(Map<String,Object> rule,int expected) {
        if(!"DISTANCE".equals(rule.get("geometry_rule"))
                || !(rule.get("legal_distance_m") instanceof Number distance)
                || distance.doubleValue()!=expected) invalid();
    }
    private static void invalid() { throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); }
}
