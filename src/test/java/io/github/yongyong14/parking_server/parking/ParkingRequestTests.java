package io.github.yongyong14.parking_server.parking;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import io.github.yongyong14.parking_server.global.exception.*;
import io.github.yongyong14.parking_server.global.security.AgreementPolicy;

class ParkingRequestTests {
    final ParkingSettings settings=new ParkingSettings("test",List.of(),Duration.ofMinutes(2),Duration.ofMinutes(10),10,50);
    ParkingRequest request(OffsetDateTime detected, OffsetDateTime confirmed, ParkingRequest.Method method,
            ParkingRequest.Gps gps, ParkingRequest.LastKnown last, BigDecimal lat, int count) {
        return new ParkingRequest(new UUID(0,1),new UUID(0,2),detected,confirmed,method,gps,last,
                new ParkingRequest.Point(lat,new BigDecimal("127.10")),count);
    }
    final OffsetDateTime time=OffsetDateTime.parse("2026-11-07T19:00:00+09:00");
    @Test void semanticHashNormalizesOffsetsCoordinatesAndNumericScale() {
        var a=request(time,time,ParkingRequest.Method.CONFIRMED,null,null,new BigDecimal("37.123456741"),0);
        var b=request(time.withOffsetSameInstant(ZoneOffset.UTC),time.withOffsetSameInstant(ZoneOffset.UTC),
                ParkingRequest.Method.CONFIRMED,null,null,new BigDecimal("37.12345670"),0);
        a.validate(settings); b.validate(settings);
        assertThat(a.requestHash()).isEqualTo(b.requestHash());
        assertThat(a.normalized().confirmedLocation().lat()).isEqualByComparingTo("37.1234567");
        var c=request(time,time,ParkingRequest.Method.CONFIRMED,null,null,new BigDecimal("37.12345675"),0);
        assertThat(c.requestHash()).isNotEqualTo(a.requestHash());
    }
    @Test void optionalLocationsAndAccuracyParticipateInHash() {
        var a=request(time,time,ParkingRequest.Method.CONFIRMED,null,null,BigDecimal.ZERO,0);
        var b=request(time,time,ParkingRequest.Method.CONFIRMED,new ParkingRequest.Gps(BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO),null,BigDecimal.ZERO,0);
        assertThat(a.requestHash()).isNotEqualTo(b.requestHash());
        var c=request(time,time,ParkingRequest.Method.CONFIRMED,new ParkingRequest.Gps(BigDecimal.ZERO,BigDecimal.ZERO,new BigDecimal("0.00")),null,BigDecimal.ZERO,0);
        assertThat(b.requestHash()).isEqualTo(c.requestHash());
    }
    @Test void validatesRequiredTimeOrderAndAuto() {
        assertCode(request(time,time.minusNanos(1),ParkingRequest.Method.CONFIRMED,null,null,BigDecimal.ZERO,0),ErrorCode.INVALID_TIME_ORDER);
        assertCode(request(time,time,ParkingRequest.Method.AUTO,null,null,BigDecimal.ZERO,0),ErrorCode.AUTO_CONFIRM_NOT_ALLOWED);
        assertCode(request(time,time,ParkingRequest.Method.CONFIRMED,null,new ParkingRequest.LastKnown(BigDecimal.ZERO,BigDecimal.ZERO,null),BigDecimal.ZERO,0),ErrorCode.INVALID_INPUT);
    }
    @Test void lastKnownTenMinuteAndCoordinateBoundaries() {
        request(time,time,ParkingRequest.Method.ADJUSTED,null,new ParkingRequest.LastKnown(BigDecimal.ZERO,BigDecimal.ZERO,time.minusMinutes(10)),new BigDecimal("90"),10).validate(settings);
        assertCode(request(time,time,ParkingRequest.Method.ADJUSTED,null,new ParkingRequest.LastKnown(BigDecimal.ZERO,BigDecimal.ZERO,time.minusMinutes(10).minusNanos(1)),BigDecimal.ZERO,0),ErrorCode.INVALID_INPUT);
        assertCode(request(time,time,ParkingRequest.Method.CONFIRMED,null,null,new BigDecimal("90.0000001"),0),ErrorCode.INVALID_INPUT);
        assertCode(request(time,time,ParkingRequest.Method.CONFIRMED,new ParkingRequest.Gps(BigDecimal.ZERO,BigDecimal.ZERO,new BigDecimal("-0.01")),null,BigDecimal.ZERO,0),ErrorCode.INVALID_INPUT);
        assertCode(request(time,time,ParkingRequest.Method.CONFIRMED,null,null,BigDecimal.ZERO,11),ErrorCode.INVALID_INPUT);
    }
    @Test void largeFiniteDecimalAccuracyCannotBecomeInfinity() {
        assertCode(request(time,time,ParkingRequest.Method.CONFIRMED,new ParkingRequest.Gps(BigDecimal.ZERO,BigDecimal.ZERO,new BigDecimal("1E999")),null,BigDecimal.ZERO,0),ErrorCode.INVALID_INPUT);
    }
    @Test void unconfiguredAgreementVersionsFailClosed() {
        assertThat(new AgreementPolicy(Set.of()).acceptsLocationVersion("2026-11-01")).isFalse();
        assertThat(new AgreementPolicy(Set.of("explicit-test")).acceptsLocationVersion("explicit-test")).isTrue();
    }
    @Test void compactDecimalCanonicalizationPreservesNumericEqualityAndZero() {
        for (String[] equivalents:new String[][]{{"1","1.0","1e0","1.0000"},{"0","0.0","-0.00","0e-1000000","0e+1000000"}}) {
            var hashes=new HashSet<String>();
            for (String number:equivalents) {
                var r=request(time,time,ParkingRequest.Method.CONFIRMED,
                        new ParkingRequest.Gps(BigDecimal.ZERO,BigDecimal.ZERO,new BigDecimal(number)),null,BigDecimal.ZERO,0);
                hashes.add(r.validated(settings).requestHash());
            }
            assertThat(hashes).hasSize(1);
        }
    }
    @Test void extremeExponentsRemainCompactWithoutAllocatingExponentSizedBuffers() {
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(3),()->{
            for (String tiny:new String[]{"1e-1000000","1e-2147483647","0e-2147483647"}) {
                var value=new BigDecimal(tiny);
                var r=request(time,time,ParkingRequest.Method.CONFIRMED,
                        new ParkingRequest.Gps(value,value,value),null,value,0).validated(settings);
                assertThat(ParkingRequest.decimal(value)).hasSizeLessThan(32);
                assertThat(r.confirmedLocation().lat()).isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(r.requestHash()).hasSize(64);
            }
            assertThat(ParkingRequest.coordinate(new BigDecimal("0.00000005"))).isEqualTo("1E-7");
            assertThat(ParkingRequest.coordinate(new BigDecimal("-0.00000005"))).isEqualTo("-1E-7");
        });
    }
    @Test void jsonParserAndHashHandleTinyAccuracyAndRejectOutOfRangeExponentWithoutExpansion() {
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
        String template="""
                {"clientEventId":"00000000-0000-0000-0000-000000000001","vehicleId":"00000000-0000-0000-0000-000000000002",
                 "detectedAt":"2026-11-07T19:00:00+09:00","confirmedAt":"2026-11-07T19:00:00+09:00",
                 "confirmationMethod":"CONFIRMED","confirmedLocation":{"lat":37,"lng":127},
                 "originalLocation":{"lat":37,"lng":127,"accuracyM":EXPONENT},"remeasureCount":0}
                """;
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(3),()->{
            for (String tiny:List.of("1e-1000000","1e-2147483647")) {
                var r=mapper.readValue(template.replace("EXPONENT",tiny),ParkingRequest.class).validated(settings);
                assertThat(r.requestHash()).hasSize(64);
                assertThat(ParkingRequest.decimal(r.originalLocation().accuracyM())).hasSizeLessThan(32);
            }
            assertThatThrownBy(()->mapper.readValue(template.replace("EXPONENT","1e-2147483648"),ParkingRequest.class))
                    .isInstanceOf(Exception.class);
        });
    }
    @Test void allRequestTimesUseMicrosecondTruncationAndEquivalentPrecisionHasSameHash() {
        var detected=OffsetDateTime.parse("2026-11-08T02:59:59.999999999+09:00");
        var a=request(detected,detected,ParkingRequest.Method.CONFIRMED,null,
                new ParkingRequest.LastKnown(BigDecimal.ZERO,BigDecimal.ZERO,detected.minusNanos(1)),BigDecimal.ZERO,0);
        var normalized=a.validated(settings);
        assertThat(normalized.detectedAt()).isEqualTo(detected.withNano(999999000));
        assertThat(normalized.confirmedAt()).isEqualTo(detected.withNano(999999000));
        assertThat(normalized.lastKnownLocation().at()).isEqualTo(detected.withNano(999999000));
        var utc=detected.withNano(999999100).withOffsetSameInstant(ZoneOffset.UTC);
        var b=request(utc,utc,ParkingRequest.Method.CONFIRMED,null,
                new ParkingRequest.LastKnown(BigDecimal.ZERO,BigDecimal.ZERO,utc),BigDecimal.ZERO,0);
        assertThat(a.requestHash()).isEqualTo(b.requestHash()).isEqualTo(normalized.requestHash());
        var next=request(detected.withNano(0).plusSeconds(1),detected.withNano(0).plusSeconds(1),ParkingRequest.Method.CONFIRMED,null,null,BigDecimal.ZERO,0);
        assertThat(next.requestHash()).isNotEqualTo(a.requestHash());
    }
    @Test void timeOrderAndLastKnownValidationUseNormalizedInstants() {
        var detected=time.withNano(123456900);
        request(detected,detected.withNano(123456100),ParkingRequest.Method.CONFIRMED,null,
                new ParkingRequest.LastKnown(BigDecimal.ZERO,BigDecimal.ZERO,detected.withNano(123456999)),BigDecimal.ZERO,0).validated(settings);
        request(detected,detected,ParkingRequest.Method.CONFIRMED,null,
                new ParkingRequest.LastKnown(BigDecimal.ZERO,BigDecimal.ZERO,detected.minusMinutes(10).withNano(123456000)),BigDecimal.ZERO,0).validated(settings);
        assertCode(request(detected,detected.withNano(123455999),ParkingRequest.Method.CONFIRMED,null,null,BigDecimal.ZERO,0),ErrorCode.INVALID_TIME_ORDER);
        assertCode(request(detected,detected,ParkingRequest.Method.CONFIRMED,null,
                new ParkingRequest.LastKnown(BigDecimal.ZERO,BigDecimal.ZERO,detected.withNano(123457000)),BigDecimal.ZERO,0),ErrorCode.INVALID_INPUT);
        assertCode(request(detected,detected,ParkingRequest.Method.CONFIRMED,null,
                new ParkingRequest.LastKnown(BigDecimal.ZERO,BigDecimal.ZERO,detected.minusMinutes(10).withNano(123455999)),BigDecimal.ZERO,0),ErrorCode.INVALID_INPUT);
    }
    @Test void jsonKeyOrderAbsentOptionalFieldsAndOffsetsHaveSameHash() {
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
        String a="""
                {"clientEventId":"00000000-0000-0000-0000-000000000001","vehicleId":"00000000-0000-0000-0000-000000000002",
                 "detectedAt":"2026-11-07T19:00:00+09:00","confirmedAt":"2026-11-07T19:00:00+09:00",
                 "confirmationMethod":"CONFIRMED","confirmedLocation":{"lat":37,"lng":127},"remeasureCount":0}
                """;
        String b="""
                {"remeasureCount":0,"confirmedLocation":{"lng":127.000,"lat":37.0},"confirmationMethod":"CONFIRMED",
                 "originalLocation":null,"lastKnownLocation":null,"confirmedAt":"2026-11-07T10:00:00Z",
                 "detectedAt":"2026-11-07T10:00:00Z","vehicleId":"00000000-0000-0000-0000-000000000002",
                 "clientEventId":"00000000-0000-0000-0000-000000000001"}
                """;
        var first=mapper.readValue(a,ParkingRequest.class); var second=mapper.readValue(b,ParkingRequest.class);
        first.validate(settings); second.validate(settings);
        assertThat(first.requestHash()).isEqualTo(second.requestHash());
        assertThatThrownBy(()->mapper.readValue(a.replace("\"lat\":37","\"lat\":NaN"),ParkingRequest.class)).isInstanceOf(Exception.class);
        assertThatThrownBy(()->mapper.readValue(a.replace("\"lat\":37","\"lat\":Infinity"),ParkingRequest.class)).isInstanceOf(Exception.class);
        assertThatThrownBy(()->mapper.readValue(a.replace("2026-11-07T19:00:00+09:00","2026-11-07T19:00:00"),ParkingRequest.class)).isInstanceOf(Exception.class);
    }
    void assertCode(ParkingRequest r, ErrorCode code) {
        assertThatThrownBy(()->r.validate(settings)).isInstanceOf(BusinessException.class)
                .extracting(e->((BusinessException)e).getErrorCode()).isEqualTo(code);
    }
}
