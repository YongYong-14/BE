package io.github.yongyong14.parking_server.parking;

import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ParkingNotificationPolicyTests {
    final ParkingNotificationPolicy policy=new ParkingNotificationPolicy(2);
    final Instant detected=OffsetDateTime.parse("2026-11-07T22:00:00+09:00").toInstant();
    String decision(String status,Integer level,Double top,Instant now,boolean caution,boolean night) {
        return policy.decide(status,level,top,detected,now,caution,night);
    }
    @Test void staleTakesPriorityEvenOverLevelOneOrImmediatePercentile() {
        var stale=detected.plusSeconds(1800).plusNanos(1);
        assertThat(decision("SUCCEEDED",1,null,stale,false,false)).isEqualTo("NONE");
        assertThat(decision("SUCCEEDED",2,0.0,stale,false,false)).isEqualTo("NONE");
        assertThat(decision("SUCCEEDED",1,null,detected.plusSeconds(1800),false,false)).isEqualTo("SEND");
    }
    @Test void levelOneAndExactTwoPercentIgnoreBothSettings() {
        assertThat(decision("SUCCEEDED",1,null,detected,false,false)).isEqualTo("SEND");
        assertThat(decision("SUCCEEDED",2,2.0,detected,false,false)).isEqualTo("SEND");
        assertThat(decision("SUCCEEDED",2,0.0,detected,false,false)).isEqualTo("SEND");
        assertThat(decision("SUCCEEDED",2,2.00000001,detected,false,false)).isEqualTo("SUPPRESSED_SETTING");
    }
    @Test void settingSuppressionPrecedesNightSuppression() {
        assertThat(decision("SUCCEEDED",2,8.0,detected,false,false)).isEqualTo("SUPPRESSED_SETTING");
        assertThat(decision("SUCCEEDED",2,8.0,detected,true,false)).isEqualTo("SUPPRESSED_NIGHT");
        assertThat(decision("SUCCEEDED",2,8.0,detected,true,true)).isEqualTo("SEND");
    }
    @Test void kstNightBoundaryIsTwentyTwoInclusiveToSevenExclusive() {
        for (String hour:new String[]{"21:59:59","22:00:00","06:59:59","07:00:00"}) {
            var time=OffsetDateTime.parse("2026-11-07T"+hour+"+09:00").toInstant();
            assertThat(policy.decide("SUCCEEDED",2,8.0,time,time,true,false))
                    .isEqualTo(hour.equals("22:00:00") || hour.equals("06:59:59") ? "SUPPRESSED_NIGHT" : "SEND");
        }
    }
    @Test void levelThreePartialAndFailedNeverSendAndThresholdMustFitVersion() {
        assertThat(decision("SUCCEEDED",3,70.0,detected,true,true)).isEqualTo("NONE");
        assertThat(decision("PARTIAL",null,null,detected,true,true)).isEqualTo("NONE");
        assertThat(decision("FAILED",null,null,detected,true,true)).isEqualTo("NONE");
        assertThatThrownBy(()->policy.validateBoundary(1.9)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new ParkingNotificationPolicy(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }
}
