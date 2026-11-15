package io.github.yongyong14.parking_server.parking;

/** Internal diagnostics contain only allowlisted codes, never configuration values or infrastructure messages. */
final class ParkingConfigurationException extends RuntimeException {
    enum Code {
        PUBLIC_RULE_SET_UNAVAILABLE,
        MODEL_VERSION_NOT_FROZEN,
        FACILITY_RULE_CONTRACT_MISMATCH
    }
    private final Code code;
    ParkingConfigurationException(Code code) {
        super(java.util.Objects.requireNonNull(code).name());
        this.code=code;
    }
    Code code() { return code; }
}
