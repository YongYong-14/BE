package io.github.yongyong14.parking_server.map;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import io.github.yongyong14.parking_server.global.exception.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MapRiskServiceTests {
    final MapRiskRepository repo=mock(MapRiskRepository.class);
    final Instant now=Instant.parse("2026-11-06T18:00:00Z");
    final MapRiskService service=new MapRiskService(repo,Clock.fixed(now,ZoneOffset.UTC),"selected");
    final MapBounds bounds=new MapBounds(37.32,127.09,37.33,127.10);
    final MapRiskRepository.Version version=new MapRiskRepository.Version(UUID.randomUUID(),UUID.randomUUID(),
            LocalDate.of(2024,1,1),LocalDate.of(2024,9,25),"AS_SUNDAY",8.125,5186,50);
    @BeforeEach void setup() {
        when(repo.withinLimit(bounds)).thenReturn(true);
        when(repo.active()).thenReturn(Optional.of(version));
        when(repo.facilities(bounds,"selected")).thenReturn(List.of());
    }
    @Test void validatesFiniteCoordinatesAndOrdering() {
        for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
            assertThrows(BusinessException.class,()->new MapBounds(bad,127,38,128));
            assertThrows(BusinessException.class,()->new MapBounds(37,bad,38,128));
            assertThrows(BusinessException.class,()->new MapBounds(37,127,bad,128));
            assertThrows(BusinessException.class,()->new MapBounds(37,127,38,bad));
        }
        assertThrows(BusinessException.class,()->new MapBounds(-91,0,0,1));
        assertThrows(BusinessException.class,()->new MapBounds(0,-181,1,0));
        assertThrows(BusinessException.class,()->new MapBounds(0,0,91,1));
        assertThrows(BusinessException.class,()->new MapBounds(0,0,1,181));
        assertThrows(BusinessException.class,()->new MapBounds(38,127,37,128));
        assertThrows(BusinessException.class,()->new MapBounds(37,128,38,127));
    }
    @Test void clockHolidaySlotThresholdAndPeriodUseOneVersion() {
        var slot=new MapRiskResponse.Slot(7,1);
        when(repo.holiday(LocalDate.of(2026,11,7))).thenReturn(true);
        when(repo.predictions(bounds,version,slot)).thenReturn(List.of(
                new MapRiskRepository.Prediction(1,new MapRiskResponse.Location(37.32,127.09),50,8.125,12L),
                new MapRiskRepository.Prediction(2,new MapRiskResponse.Location(37.32,127.09),50,8.12501,99L)));
        var response=service.get(bounds,null);
        assertEquals(slot,response.slot());
        assertEquals(1,response.cells().size());
        assertEquals(12,response.cells().getFirst().reportCount());
        assertEquals(new MapRiskResponse.DataPeriod(version.start(),version.end()),response.dataPeriod());
        verify(repo,times(1)).active();
        verify(repo).requireRules("selected",now);
        verify(repo).requireStatistics(version);
    }
    @Test void noVersionHasActualKstSlotAndNullPeriod() {
        when(repo.active()).thenReturn(Optional.empty());
        var response=service.get(bounds,null);
        assertEquals(new MapRiskResponse.Slot(6,1),response.slot());
        assertNull(response.dataPeriod()); assertTrue(response.cells().isEmpty());
        verify(repo,never()).holiday(any());
        verify(repo,never()).predictions(any(),any(),any());
    }
    @Test void missingPredictionIsUnavailable() {
        var slot=new MapRiskResponse.Slot(6,1);
        when(repo.predictions(bounds,version,slot)).thenReturn(List.of(
                new MapRiskRepository.Prediction(1,new MapRiskResponse.Location(37,127),50,null,null)));
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE,assertThrows(BusinessException.class,
                ()->service.get(bounds,null)).getErrorCode());
    }
    @Test void sizeAndRulesFailuresAreNotEmptySuccess() {
        when(repo.withinLimit(bounds)).thenReturn(false);
        assertEquals(ErrorCode.INVALID_INPUT,assertThrows(BusinessException.class,
                ()->service.get(bounds,null)).getErrorCode());
        when(repo.withinLimit(bounds)).thenReturn(true);
        assertThrows(BusinessException.class,()->new MapRiskService(repo,Clock.systemUTC(),"").get(bounds,null));
        doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE)).when(repo).requireRules(any(),any());
        assertThrows(BusinessException.class,()->service.get(bounds,null));
    }
    @Test void kstBoundariesAndEquivalentInstantsAndPolicies() {
        assertEquals(new MapRiskResponse.Slot(1,0),MapTime.slot(Instant.parse("2026-11-08T15:00:00Z"),"NONE",true));
        assertEquals(new MapRiskResponse.Slot(7,7),MapTime.slot(Instant.parse("2026-11-08T14:59:59Z"),"NONE",false));
        for(int hour=0;hour<24;hour++) {
            var time=OffsetDateTime.of(2026,11,6,hour,0,0,0,ZoneOffset.ofHours(9));
            assertEquals(hour/3,MapTime.slot(time.toInstant(),"NONE",false).timeSlot());
            assertEquals(MapTime.slot(time.toInstant(),"NONE",false),
                    MapTime.slot(time.withOffsetSameInstant(ZoneOffset.ofHours(-5)).toInstant(),"NONE",false));
        }
        assertThrows(BusinessException.class,()->MapTime.slot(now,"UNKNOWN",false));
    }
}
