package io.github.yongyong14.parking_server.map;

import io.github.yongyong14.parking_server.account.MockAccountRepositories;
import java.util.*;
import io.github.yongyong14.parking_server.global.security.*;
import io.github.yongyong14.parking_server.global.exception.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@MockAccountRepositories
@SpringBootTest
@AutoConfigureMockMvc
class MapRiskHttpTests {
    @Autowired MockMvc mvc;
    @Autowired AccessTokenService tokens;
    @MockitoBean UserExistence users;
    @MockitoBean MapRiskService service;
    String bearer;
    @BeforeEach void setup() {
        var user=UUID.randomUUID(); when(users.exists(user)).thenReturn(true);
        bearer="Bearer "+tokens.issue(user).accessToken();
        when(service.get(any(),any())).thenReturn(new MapRiskResponse(List.of(),List.of(),
                new MapRiskResponse.Slot(6,6),null));
    }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request() {
        return get("/api/v1/map/risk-zones").header("Authorization",bearer)
                .param("minLat","37.32").param("minLng","127.09").param("maxLat","37.33").param("maxLng","127.10");
    }
    @Test void authenticationAndNoConsentLookup() throws Exception {
        mvc.perform(get("/api/v1/map/risk-zones")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mvc.perform(request()).andExpect(status().isOk()).andExpect(jsonPath("$.data.slot.weekday").value(6))
                .andExpect(jsonPath("$.data.slot.timeSlot").value(6)).andExpect(jsonPath("$.data.dataPeriod").isEmpty());
        verify(service).get(new MapBounds(37.32,127.09,37.33,127.10),null);
    }
    @Test void malformedNumbersAndTimeAreInvalidInput() throws Exception {
        for(String number:new String[]{"no","NaN","Infinity","-Infinity","91","38"}) {
            mvc.perform(get("/api/v1/map/risk-zones").header("Authorization",bearer)
                    .param("minLat",number).param("minLng","127").param("maxLat","37.33").param("maxLng","127.01"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        }
        mvc.perform(request().param("at","2026-11-06T12:00:00")).andExpect(status().isBadRequest());
        mvc.perform(request().param("at","bad")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/map/risk-zones").header("Authorization",bearer)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void dependencyAndDatabaseFailuresUseCommonErrors() throws Exception {
        when(service.get(any(),any())).thenThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));
        mvc.perform(request()).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("test")).when(service).get(any(),any());
        mvc.perform(request()).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"));
    }
    @Test void actualFieldsAndGeneratedOpenApiAgree() throws Exception {
        when(service.get(any(),any())).thenReturn(new MapRiskResponse(
                List.of(new MapRiskResponse.FacilityZone("HYDRANT",new MapRiskResponse.Location(37.32,127.09),5)),
                List.of(new MapRiskResponse.Cell(new MapRiskResponse.Location(37.32,127.09),50,8.0,12)),
                new MapRiskResponse.Slot(6,6),new MapRiskResponse.DataPeriod(java.time.LocalDate.of(2025,1,1),java.time.LocalDate.of(2025,9,25))));
        mvc.perform(request()).andExpect(status().isOk()).andExpect(jsonPath("$.data.dataPeriod.start").value("2025-01-01"))
                .andExpect(jsonPath("$.data.dataPeriod.end").value("2025-09-25"))
                .andExpect(jsonPath("$.data.cells[0].reportCount").value(12)).andExpect(jsonPath("$.data.facilityZones[0].radiusM").value(5));
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/map/risk-zones'].get.description").value(org.hamcrest.Matchers.containsString("legal_distance_m")))
                .andExpect(jsonPath("$.paths['/api/v1/map/risk-zones'].get.responses['503']").exists())
                .andExpect(jsonPath("$.components.schemas.Slot.properties.weekday").exists())
                .andExpect(jsonPath("$.components.schemas.Slot.properties.timeSlot").exists())
                .andExpect(jsonPath("$.components.schemas.DataPeriod.properties.start").exists())
                .andExpect(jsonPath("$.components.schemas.DataPeriod.properties.end").exists())
                .andExpect(jsonPath("$.components.schemas.MapRiskResponse.properties.dataPeriod.oneOf[1].type").value("null"))
                .andExpect(jsonPath("$.components.schemas.MapRiskSuccess.required").value(org.hamcrest.Matchers.containsInAnyOrder("success","data","error")))
                .andExpect(jsonPath("$.paths['/api/v1/map/risk-zones'].get.responses['200'].content['application/json'].schema.$ref").value("#/components/schemas/MapRiskSuccess"));
    }
    @Test void outsideFacilityCentersAreReturnedForPointLineAndPolygonViewports() throws Exception {
        for(var bounds:List.of(new MapBounds(37.32,127.09,37.32,127.09),
                new MapBounds(37.32,127.09,37.32,127.091),
                new MapBounds(37.32,127.09,37.321,127.09),
                new MapBounds(37.32,127.09,37.321,127.091))) {
            when(service.get(eq(bounds),isNull())).thenReturn(new MapRiskResponse(List.of(
                    new MapRiskResponse.FacilityZone("HYDRANT",new MapRiskResponse.Location(37.32,127.08999),5),
                    new MapRiskResponse.FacilityZone("BUS_STOP",new MapRiskResponse.Location(37.32,127.08998),10)),
                    List.of(),new MapRiskResponse.Slot(6,6),null));
            mvc.perform(get("/api/v1/map/risk-zones").header("Authorization",bearer)
                    .param("minLat",Double.toString(bounds.minLat())).param("maxLat",Double.toString(bounds.maxLat()))
                    .param("minLng",Double.toString(bounds.minLng())).param("maxLng",Double.toString(bounds.maxLng())))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.facilityZones.length()").value(2))
                    .andExpect(jsonPath("$.data.facilityZones[0].location.lng").value(127.08999))
                    .andExpect(jsonPath("$.data.facilityZones[0].radiusM").value(5))
                    .andExpect(jsonPath("$.data.facilityZones[1].location.lng").value(127.08998))
                    .andExpect(jsonPath("$.data.facilityZones[1].radiusM").value(10))
                    .andExpect(jsonPath("$.data.cells").isEmpty());
            verify(service).get(bounds,null);
        }
    }
}
