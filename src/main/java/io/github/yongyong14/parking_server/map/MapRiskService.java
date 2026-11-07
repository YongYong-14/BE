package io.github.yongyong14.parking_server.map;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import io.github.yongyong14.parking_server.global.exception.*;

@Service
@Lazy
public class MapRiskService {
    private final MapRiskRepository repository;
    private final Clock clock;
    private final String rules;
    public MapRiskService(@Lazy MapRiskRepository repository, Clock clock,
            @Value("${app.parking.rule-set:}") String rules) {
        this.repository=repository; this.clock=clock; this.rules=rules;
    }
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public MapRiskResponse get(MapBounds bounds, OffsetDateTime at) {
        Instant now=clock.instant();
        Instant instant=at==null ? now : at.toInstant();
        if (!repository.withinLimit(bounds)) throw new BusinessException(ErrorCode.INVALID_INPUT);
        if (rules.isBlank()) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        repository.requireRules(rules,now);
        var version=repository.active();
        String policy=version.map(MapRiskRepository.Version::policy).orElse("NONE");
        var slot=MapTime.slot(instant,policy,policy.equals("AS_SUNDAY")
                && repository.holiday(instant.atZone(MapTime.SEOUL).toLocalDate()));
        var facilities=repository.facilities(bounds,rules);
        if (version.isEmpty()) return new MapRiskResponse(facilities,List.of(),slot,null);
        var v=version.get();
        repository.requireStatistics(v);
        var cells=new ArrayList<MapRiskResponse.Cell>();
        for (var prediction:repository.predictions(bounds,v,slot)) {
            if (prediction.topPercent()==null || !Double.isFinite(prediction.topPercent())
                    || prediction.topPercent()<0 || prediction.topPercent()>100)
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
            if (prediction.topPercent()<=v.threshold()) cells.add(new MapRiskResponse.Cell(
                    prediction.location(),prediction.size(),prediction.topPercent(),
                    prediction.reports()==null ? 0 : prediction.reports()));
        }
        return new MapRiskResponse(facilities,cells,slot,new MapRiskResponse.DataPeriod(v.start(),v.end()));
    }
}
