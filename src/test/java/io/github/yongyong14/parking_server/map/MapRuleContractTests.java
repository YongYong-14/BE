package io.github.yongyong14.parking_server.map;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapRuleContractTests {
    private Map<String,Object> rule(String type,String geometry,Double distance,Integer fine) {
        var r=new HashMap<String,Object>(); r.put("zone_type",type);r.put("geometry_rule",geometry);
        r.put("legal_distance_m",distance);r.put("fine_amount_car",fine); return r;
    }
    @Test void matchesParkingRuleContractIncludingOther() {
        var h=rule("HYDRANT","DISTANCE",5.0,80000);
        var b=rule("BUS_STOP","DISTANCE",10.0,40000);
        var o=rule("OTHER","COVERS",null,47000);
        assertDoesNotThrow(()->MapRuleContract.validate(List.of(h,b,o)));
        assertThrows(RuntimeException.class,()->MapRuleContract.validate(List.of(h,b)));
        assertThrows(RuntimeException.class,()->MapRuleContract.validate(List.of(h,b,o,o)));
        for(var invalid:List.of(rule("OTHER","DISTANCE",null,40000),rule("OTHER","COVERS",1.0,40000),
                rule("OTHER","COVERS",null,null),rule("OTHER","COVERS",null,-1)))
            assertThrows(RuntimeException.class,()->MapRuleContract.validate(List.of(h,b,invalid)));
        assertThrows(RuntimeException.class,()->MapRuleContract.validate(List.of(rule("HYDRANT","DISTANCE",6.0,80000),b,o)));
    }
}
