package io.github.yongyong14.parking_server.parking;

import io.github.yongyong14.parking_server.account.MockAccountRepositories;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import io.github.yongyong14.parking_server.global.security.*;
import io.github.yongyong14.parking_server.global.exception.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@MockAccountRepositories
@SpringBootTest
@AutoConfigureMockMvc
class ParkingQueryHttpTests {
    @Autowired MockMvc mvc;
    @Autowired AccessTokenService tokens;
    @MockitoBean UserExistence users;
    @MockitoBean ParkingQueryTransactions queries;
    @MockitoBean ParkingDeliveryTransactions delivery;
    UUID user=UUID.randomUUID(); String bearer;
    @BeforeEach void setup() { when(users.exists(user)).thenReturn(true); bearer="Bearer "+tokens.issue(user).accessToken(); }
    @Test void authenticationAndMalformedId() throws Exception {
        for (String path:List.of("/latest","/"+UUID.randomUUID(),""))
            mvc.perform(get("/api/v1/parking-events"+path)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/parking-events/not-a-uuid").header("Authorization",bearer))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        verifyNoInteractions(queries);
    }
    @Test void latestRoutesWithoutUuidAndIncludesEmptyFields() throws Exception {
        when(queries.latest(user)).thenReturn(new ParkingQueryResponse.Latest(null,null,null));
        mvc.perform(get("/api/v1/parking-events/latest").header("Authorization",bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.event").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.assessment").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.dataPeriod").value(org.hamcrest.Matchers.nullValue()));
        verify(queries).latest(user);
    }
    @Test void listDefaultsBoundariesAndInvalidInput() throws Exception {
        when(queries.list(eq(user),anyString(),anyInt())).thenReturn(new ParkingQueryResponse.Items(List.of()));
        mvc.perform(get("/api/v1/parking-events").header("Authorization",bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
        verify(queries).list(user,"ALL",50);
        for (int limit:List.of(1,100))
            mvc.perform(get("/api/v1/parking-events").header("Authorization",bearer).param("limit",""+limit).param("level","2"))
                    .andExpect(status().isOk());
        for (String limit:List.of("","0","101","-1","1.5","abc"))
            mvc.perform(get("/api/v1/parking-events").header("Authorization",bearer).param("limit",limit))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        for (String level:List.of("","all","3","01"))
            mvc.perform(get("/api/v1/parking-events").header("Authorization",bearer).param("level",level))
                    .andExpect(status().isBadRequest());
    }
    @Test void missingAndDatabaseFailureAreDistinct() throws Exception {
        UUID id=UUID.randomUUID();
        when(queries.detail(user,id)).thenThrow(new BusinessException(ErrorCode.PARKING_EVENT_NOT_FOUND));
        mvc.perform(get("/api/v1/parking-events/"+id).header("Authorization",bearer))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("PARKING_EVENT_NOT_FOUND"));
        when(queries.latest(user)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private SQL binding"));
        mvc.perform(get("/api/v1/parking-events/latest").header("Authorization",bearer))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
    }
    @Test void generatedOpenApiDescribesQueriesWithoutChangingPost() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/parking-events/latest'].get.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/parking-events/{id}'].get.responses['404']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/parking-events'].get.responses['400']").exists())
                .andExpect(jsonPath("$.components.schemas.ParkingLatest.required").value(org.hamcrest.Matchers.hasItems("event","assessment","dataPeriod")))
                .andExpect(jsonPath("$.components.schemas.ParkingLatest.properties.event.oneOf[1].type").value("null"))
                .andExpect(jsonPath("$.components.schemas.ParkingLatest.properties.dataPeriod.oneOf[1].type").value("null"))
                .andExpect(jsonPath("$.components.schemas.ParkingDetailStats.required").value(org.hamcrest.Matchers.hasItem("peak")))
                .andExpect(jsonPath("$.components.schemas.ParkingDetailStats.properties.peak.oneOf[1].type").value("null"))
                .andExpect(jsonPath("$.components.schemas.ParkingStats.properties.peak").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.ParkingPeak.properties.slotCounts.minItems").value(8))
                .andExpect(jsonPath("$.components.schemas.ParkingPeak.properties.slotCounts.maxItems").value(8));
    }
    @Test void deliveryAuthenticationExclusiveInputFailuresAndNoContent() throws Exception {
        String path="/api/v1/parking-events/"+UUID.randomUUID()+"/delivery";
        mvc.perform(patch(path).contentType("application/json").content("{\"failure\":\"UNKNOWN\"}"))
                .andExpect(status().isUnauthorized());
        for (String body:List.of("{}","{\"failure\":\"\"}","{\"failure\":\"arbitrary\"}",
                "{\"failure\":\"UNKNOWN\",\"deliveredAt\":\"2026-11-07T10:05:41Z\"}",
                "{\"deliveredAt\":\"2026-11-07T10:05:41\"}","{\"deliveredAt\":null,\"failure\":null}"))
            mvc.perform(patch(path).header("Authorization",bearer).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        verifyNoInteractions(delivery);
        mvc.perform(patch("/api/v1/parking-events/not-a-uuid/delivery").header("Authorization",bearer).contentType("application/json").content("{\"failure\":\"UNKNOWN\"}"))
                .andExpect(status().isBadRequest());
        for (String failure:List.of("NO_PERMISSION","DISPLAY_FAILED","UNKNOWN"))
            mvc.perform(patch(path).header("Authorization",bearer).contentType("application/json").content("{\"failure\":\""+failure+"\"}"))
                    .andExpect(status().isNoContent()).andExpect(content().string(""));
        doThrow(new BusinessException(ErrorCode.DELIVERY_NOT_EXPECTED)).when(delivery).report(eq(user),any(),any());
        mvc.perform(patch(path).header("Authorization",bearer).contentType("application/json").content("{\"failure\":\"UNKNOWN\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DELIVERY_NOT_EXPECTED"));
        doThrow(new BusinessException(ErrorCode.PARKING_EVENT_NOT_FOUND)).when(delivery).report(eq(user),any(),any());
        mvc.perform(patch(path).header("Authorization",bearer).contentType("application/json").content("{\"failure\":\"UNKNOWN\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("PARKING_EVENT_NOT_FOUND"));
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/parking-events/{id}/delivery'].patch.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/parking-events/{id}/delivery'].patch.responses['204'].content").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.ParkingDeliveryRequest.oneOf").isArray());
    }
}
