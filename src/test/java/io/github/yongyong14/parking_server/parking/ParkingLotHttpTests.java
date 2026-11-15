package io.github.yongyong14.parking_server.parking;

import java.util.*;
import io.github.yongyong14.parking_server.account.MockAccountRepositories;
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
class ParkingLotHttpTests {
    @Autowired MockMvc mvc;
    @Autowired AccessTokenService tokens;
    @MockitoBean UserExistence users;
    @MockitoBean ParkingLotService service;
    UUID user;
    String bearer;
    @BeforeEach void setup() {
        user=UUID.randomUUID(); when(users.exists(user)).thenReturn(true);
        bearer="Bearer "+tokens.issue(user).accessToken();
        when(service.nearby(any(),anyDouble(),anyDouble(),anyInt())).thenReturn(new ParkingLotResponse(500,false,List.of()));
    }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request() {
        return get("/api/v1/parking-lots/nearby").header("Authorization",bearer).param("lat","37.32").param("lng","127.09");
    }
    @Test void authenticatedDefaultAndEmptyEnvelope() throws Exception {
        mvc.perform(get("/api/v1/parking-lots/nearby")).andExpect(status().isUnauthorized());
        mvc.perform(request()).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.radiusM").value(500)).andExpect(jsonPath("$.data.expanded").value(false))
                .andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.error").isEmpty());
        verify(service).nearby(user,37.32,127.09,500);
    }
    @Test void invalidInputNeverReachesService() throws Exception {
        for (String value:List.of("no","NaN","Infinity","-Infinity","91","-91"))
            mvc.perform(get("/api/v1/parking-lots/nearby").header("Authorization",bearer).param("lat",value).param("lng","127"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        for (String value:List.of("0","-1","1001","no","1.5"))
            mvc.perform(request().param("radius",value)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/parking-lots/nearby").header("Authorization",bearer)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/parking-lots/nearby").header("Authorization",bearer).param("lat","0").param("lng","181"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void consentFailureAndDatabaseFailure() throws Exception {
        when(service.nearby(any(),anyDouble(),anyDouble(),anyInt())).thenThrow(new BusinessException(ErrorCode.LOCATION_CONSENT_REQUIRED));
        mvc.perform(request()).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("LOCATION_CONSENT_REQUIRED"));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("private input")).when(service).nearby(any(),anyDouble(),anyDouble(),anyInt());
        mvc.perform(request()).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"));
    }
    @Test void fieldsNullsAndSwaggerContract() throws Exception {
        when(service.nearby(any(),anyDouble(),anyDouble(),anyInt())).thenReturn(new ParkingLotResponse(1000,true,List.of(
                new ParkingLotResponse.Item(UUID.randomUUID(),"fixture",null,42.7,new ParkingLotResponse.Location(37.32,127.09),"PUBLIC",null,null,null,null))));
        mvc.perform(request()).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].location.lat").value(37.32))
                .andExpect(jsonPath("$.data.items[0]",org.hamcrest.Matchers.aMapWithSize(10)))
                .andExpect(jsonPath("$.data.items[0].capacity").hasJsonPath()).andExpect(jsonPath("$.data.items[0].capacity").isEmpty())
                .andExpect(jsonPath("$.data.items[0].feeType").isEmpty()).andExpect(jsonPath("$.data.items[0].operatingHours").isEmpty());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/parking-lots/nearby'].get.responses['403']").exists())
                .andExpect(jsonPath("$.components.schemas.ParkingLotData.properties.items.items.properties.operatingHours.type")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("string","null")))
                .andExpect(jsonPath("$.components.schemas.ParkingLotData.properties.items.items.required").isArray());
    }
}
