package io.github.yongyong14.parking_server.vehicle;

import io.github.yongyong14.parking_server.global.exception.*;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import org.hibernate.validator.constraints.CodePointLength;

public record VehicleRequest(
        @NotBlank @CodePointLength(max = 100) @Schema(maxLength = 100, requiredMode = Schema.RequiredMode.REQUIRED, description = "Unicode 코드 포인트 최대 100자. 공백만인 이름은 허용하지 않으며 앞뒤 공백은 보존") String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^([0-9A-F]{2}:){5}[0-9A-F]{2}$", description = "변환 없이 대문자 MAC 형식 검증") String bluetoothAddress,
        @CodePointLength(max = 50) @Schema(types = {"string", "null"}, maxLength = 50, requiredMode = Schema.RequiredMode.NOT_REQUIRED, description = "생략·null 허용. Unicode 코드 포인트 최대 50자. 허용 목록 제한 없음") String deviceType) {
    void validateAddress() {
        if (bluetoothAddress == null || !bluetoothAddress.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}"))
            throw new BusinessException(ErrorCode.INVALID_BLUETOOTH_ADDRESS);
    }
    @Override public String toString() { return "VehicleRequest[redacted]"; }
}
