package io.github.yongyong14.parking_server.parking;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import io.github.yongyong14.parking_server.global.exception.*;

/** Input only. Never include request coordinates in logs or exception messages. */
public record ParkingRequest(UUID clientEventId, UUID vehicleId, OffsetDateTime detectedAt,
        OffsetDateTime confirmedAt, Method confirmationMethod, Gps originalLocation,
        LastKnown lastKnownLocation, Point confirmedLocation,
        @tools.jackson.databind.annotation.JsonDeserialize(using=StrictCountDeserializer.class) Integer remeasureCount) {
    public enum Method { CONFIRMED, ADJUSTED, AUTO }
    public record Point(BigDecimal lat, BigDecimal lng) {}
    public record Gps(BigDecimal lat, BigDecimal lng, BigDecimal accuracyM) {}
    public record LastKnown(BigDecimal lat, BigDecimal lng, OffsetDateTime at) {}
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void unknownField(String name,Object value) { fail(ErrorCode.INVALID_INPUT); }
    public static class StrictCountDeserializer extends tools.jackson.databind.deser.std.StdDeserializer<Integer> {
        public StrictCountDeserializer() { super(Integer.class); }
        @Override public Integer deserialize(tools.jackson.core.JsonParser parser,tools.jackson.databind.DeserializationContext context) {
            if (parser.currentToken()!=tools.jackson.core.JsonToken.VALUE_NUMBER_INT)
                throw new IllegalArgumentException("remeasureCount must be an integer");
            return parser.getIntValue();
        }
    }

    public void validate(ParkingSettings settings) {
        require(clientEventId != null && vehicleId != null && detectedAt != null && confirmedAt != null
                && confirmationMethod != null && confirmedLocation != null && remeasureCount != null);
        require(remeasureCount >= 0 && remeasureCount <= settings.maxRemeasureCount());
        coordinate(confirmedLocation.lat, confirmedLocation.lng);
        if (originalLocation != null) {
            coordinate(originalLocation.lat, originalLocation.lng);
            require(originalLocation.accuracyM != null && originalLocation.accuracyM.signum() >= 0
                    && Double.isFinite(originalLocation.accuracyM.doubleValue()));
        }
        if (lastKnownLocation != null) {
            coordinate(lastKnownLocation.lat, lastKnownLocation.lng);
            require(lastKnownLocation.at != null);
            Instant at = instant(lastKnownLocation.at);
            require(!at.isAfter(instant(detectedAt))
                    && !at.isBefore(instant(detectedAt).minus(settings.lastKnownMaxAge())));
        }
        if (instant(confirmedAt).isBefore(instant(detectedAt))) fail(ErrorCode.INVALID_TIME_ORDER);
        if (confirmationMethod == Method.AUTO && originalLocation == null) fail(ErrorCode.AUTO_CONFIRM_NOT_ALLOWED);
    }

    static void coordinate(BigDecimal lat, BigDecimal lng) {
        require(lat != null && lng != null && lat.compareTo(BigDecimal.valueOf(-90)) >= 0
                && lat.compareTo(BigDecimal.valueOf(90)) <= 0 && lng.compareTo(BigDecimal.valueOf(-180)) >= 0
                && lng.compareTo(BigDecimal.valueOf(180)) <= 0);
    }
    static void require(boolean condition) { if (!condition) fail(ErrorCode.INVALID_INPUT); }
    static void fail(ErrorCode code) { throw new BusinessException(code); }
    // Compact scientific notation bounds output by input precision, not exponent magnitude.
    static String decimal(BigDecimal value) { return value == null ? "null" : value.stripTrailingZeros().toString(); }
    static String coordinate(BigDecimal value) { return decimal(round(value)); }
    static Instant instant(OffsetDateTime value) { return value.toInstant().truncatedTo(ChronoUnit.MICROS); }
    private static OffsetDateTime time(OffsetDateTime value) { return value==null ? null : value.truncatedTo(ChronoUnit.MICROS); }

    /** Time precision is normalized before relational validation; coordinate bounds are checked before rounding. */
    public ParkingRequest validated(ParkingSettings settings) {
        var timed=new ParkingRequest(clientEventId,vehicleId,time(detectedAt),time(confirmedAt),confirmationMethod,
                originalLocation,lastKnownLocation==null ? null : new LastKnown(lastKnownLocation.lat,lastKnownLocation.lng,time(lastKnownLocation.at)),
                confirmedLocation,remeasureCount);
        timed.validate(settings);
        return timed.normalized();
    }

    public ParkingRequest normalized() {
        return new ParkingRequest(clientEventId,vehicleId,time(detectedAt),time(confirmedAt),confirmationMethod,
                originalLocation==null ? null : new Gps(round(originalLocation.lat),round(originalLocation.lng),originalLocation.accuracyM),
                lastKnownLocation==null ? null : new LastKnown(round(lastKnownLocation.lat),round(lastKnownLocation.lng),time(lastKnownLocation.at)),
                new Point(round(confirmedLocation.lat),round(confirmedLocation.lng)),remeasureCount);
    }
    private static BigDecimal round(BigDecimal value) {
        // Avoid constructing 10^scale for extremely small coordinates that necessarily round to zero.
        if (value.abs().compareTo(new BigDecimal("0.00000005"))<0) return BigDecimal.ZERO.setScale(7);
        return value.setScale(7,RoundingMode.HALF_UP);
    }

    /** Fixed field order, explicit null markers; all values have unambiguous scalar grammars. */
    public String requestHash() {
        String canonical = String.join("|", "parking-v1", clientEventId.toString(), vehicleId.toString(),
                instant(detectedAt).toString(), instant(confirmedAt).toString(), confirmationMethod.name(),
                originalLocation == null ? "null" : coordinate(originalLocation.lat) + "," + coordinate(originalLocation.lng)
                        + "," + decimal(originalLocation.accuracyM),
                lastKnownLocation == null ? "null" : coordinate(lastKnownLocation.lat) + "," + coordinate(lastKnownLocation.lng)
                        + "," + instant(lastKnownLocation.at),
                coordinate(confirmedLocation.lat) + "," + coordinate(confirmedLocation.lng), remeasureCount.toString());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    @Override public String toString() { return "ParkingRequest[redacted]"; }
}
