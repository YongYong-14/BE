package io.github.yongyong14.parking_server.map;

import io.github.yongyong14.parking_server.global.exception.*;

public record MapBounds(double minLat, double minLng, double maxLat, double maxLng) {
    public MapBounds {
        if (!Double.isFinite(minLat) || !Double.isFinite(minLng) || !Double.isFinite(maxLat)
                || !Double.isFinite(maxLng) || minLat < -90 || maxLat > 90 || minLng < -180
                || maxLng > 180 || minLat > maxLat || minLng > maxLng)
            throw new BusinessException(ErrorCode.INVALID_INPUT);
    }
}
