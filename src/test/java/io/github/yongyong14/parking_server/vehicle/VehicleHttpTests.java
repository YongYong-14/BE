package io.github.yongyong14.parking_server.vehicle;

import io.github.yongyong14.parking_server.global.security.*;
import io.github.yongyong14.parking_server.global.exception.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@io.github.yongyong14.parking_server.account.MockAccountRepositories
@SpringBootTest
@AutoConfigureMockMvc
class VehicleHttpTests {
    @Autowired MockMvc mvc;
    @Autowired AccessTokenService tokens;
    @MockitoBean UserExistence users;
    @MockitoBean VehicleTransactions transactions;
    UUID user = UUID.randomUUID();
    String bearer;
    @BeforeEach void setup() {
        when(users.exists(user)).thenReturn(true);
        bearer = "Bearer " + tokens.issue(user).accessToken();
    }
    private String body(String name, String mac, String type) {
        return "{\"name\":\"" + name + "\",\"bluetoothAddress\":\"" + mac + "\",\"deviceType\":" + type + "}";
    }
    @Test void unauthenticatedAndEmptyLookup() throws Exception {
        mvc.perform(get("/api/v1/vehicle")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/vehicle").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        when(transactions.get(user)).thenReturn(new VehicleResponse(null));
        mvc.perform(get("/api/v1/vehicle").header("Authorization", bearer).param("userId", UUID.randomUUID().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(content().json("{\"success\":true,\"data\":{\"vehicle\":null},\"error\":null}"));
        verify(transactions).get(user);
    }
    @Test void successUsesAuthenticatedIdAndCommonEnvelope() throws Exception {
        UUID vehicle = UUID.randomUUID();
        var request = new VehicleRequest("car", "AA:BB:CC:DD:EE:FF", "CUSTOM");
        when(transactions.put(user, request)).thenReturn(new VehicleResponse(new VehicleResponse.Details(vehicle, request.name(), request.bluetoothAddress(), request.deviceType())));
        mvc.perform(put("/api/v1/vehicle").header("Authorization", bearer).contentType("application/json")
                .content(body("car", "AA:BB:CC:DD:EE:FF", "\"CUSTOM\"")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.vehicle.id").value(vehicle.toString()))
                .andExpect(jsonPath("$.data.vehicle.deviceType").value("CUSTOM")).andExpect(jsonPath("$.error").isEmpty());
        verify(transactions).put(user, request);
    }
    @Test void malformedAddressesHaveSpecificCodeAndNeverReachTransactions() throws Exception {
        for (String mac : new String[]{"aa:bb:cc:dd:ee:ff", "AA-BB-CC-DD-EE-FF", "", "AA:BB:CC:DD:EE:FG"}) {
            mvc.perform(put("/api/v1/vehicle").header("Authorization", bearer).contentType("application/json")
                    .content(body("car", mac, "null"))).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_BLUETOOTH_ADDRESS"))
                    .andExpect(jsonPath("$.data").isEmpty());
        }
        mvc.perform(put("/api/v1/vehicle").header("Authorization", bearer).contentType("application/json")
                .content("{\"name\":\"car\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_BLUETOOTH_ADDRESS"));
        verifyNoInteractions(transactions);
    }
    @Test void validatesNameAndDeviceLengthAndJsonTypes() throws Exception {
        for (String name : new String[]{"", "   ", "a".repeat(101)})
            mvc.perform(put("/api/v1/vehicle").header("Authorization", bearer).contentType("application/json")
                    .content(body(name, "AA:BB:CC:DD:EE:FF", "null"))).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        for (String json : new String[]{"{}", "{\"name\":null}", body("car", "AA:BB:CC:DD:EE:FF", "\"" + "a".repeat(51) + "\""), body("car", "AA:BB:CC:DD:EE:FF", "{}")})
            mvc.perform(put("/api/v1/vehicle").header("Authorization", bearer).contentType("application/json").content(json))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        verifyNoInteractions(transactions);
    }
    @Test void documentsActualResponsesAndHidesUserId() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/vehicle'].put.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/vehicle'].put.responses['409'].$ref").value("#/components/responses/VEHICLE_CONFLICT"))
                .andExpect(jsonPath("$.paths['/api/v1/vehicle'].put.parameters").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/vehicle'].get.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/vehicle'].put.description").value(org.hamcrest.Matchers.containsString("백엔드 기본안")))
                .andExpect(jsonPath("$.components.schemas.VehicleSuccess.properties.data.properties.vehicle.oneOf[1].type").value("null"))
                .andExpect(jsonPath("$.components.schemas.VehiclePutSuccess.properties.data.properties.vehicle.$ref").value("#/components/schemas/VehicleDetails"))
                .andExpect(jsonPath("$.components.schemas.VehicleDetails.required").value(org.hamcrest.Matchers.containsInAnyOrder("id", "name", "bluetoothAddress", "deviceType")))
                .andExpect(jsonPath("$.components.schemas.VehicleRequest.properties.deviceType.enum").doesNotExist());
    }
    @Test void generatedOpenApiRequestAndResponseAllowNullDeviceType() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value(org.hamcrest.Matchers.startsWith("3.1.")))
                .andExpect(jsonPath("$.paths['/api/v1/vehicle'].put.requestBody.content['application/json'].schema.$ref").value("#/components/schemas/VehicleRequest"))
                .andExpect(jsonPath("$.components.schemas.VehicleRequest.required").value(org.hamcrest.Matchers.containsInAnyOrder("name", "bluetoothAddress")))
                .andExpect(jsonPath("$.components.schemas.VehicleRequest.properties.deviceType.type").value(org.hamcrest.Matchers.containsInAnyOrder("string", "null")))
                .andExpect(jsonPath("$.components.schemas.VehicleRequest.properties.name.maxLength").value(100))
                .andExpect(jsonPath("$.components.schemas.VehicleRequest.properties.deviceType.maxLength").value(50))
                .andExpect(jsonPath("$.components.schemas.VehicleDetails.properties.deviceType.type").value(org.hamcrest.Matchers.containsInAnyOrder("string", "null")));
    }
    @Test void nameBoundariesCountUnicodeCodePoints() throws Exception {
        String supplementary = new String(Character.toChars(0x1F697));
        for (String name : new String[]{"a".repeat(100), supplementary.repeat(100), "가".repeat(50) + supplementary.repeat(50)}) {
            var request = new VehicleRequest(name, "AA:BB:CC:DD:EE:FF", null);
            when(transactions.put(user, request)).thenReturn(new VehicleResponse(new VehicleResponse.Details(UUID.randomUUID(), name, request.bluetoothAddress(), null)));
            mvc.perform(put("/api/v1/vehicle").header("Authorization", bearer).contentType("application/json")
                    .content(body(name, request.bluetoothAddress(), "null")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.vehicle.name").value(name));
            verify(transactions).put(user, request);
        }
        clearInvocations(transactions);
        for (String name : new String[]{"a".repeat(101), supplementary.repeat(101), "가".repeat(50) + supplementary.repeat(51)}) {
            mvc.perform(put("/api/v1/vehicle").header("Authorization", bearer).contentType("application/json")
                    .content(body(name, "AA:BB:CC:DD:EE:FF", "null")))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        }
        verifyNoInteractions(transactions);
    }
    @Test void deviceTypeBoundariesCountUnicodeCodePoints() throws Exception {
        String supplementary = new String(Character.toChars(0x1F697));
        for (String type : new String[]{"a".repeat(50), supplementary.repeat(50), "가".repeat(25) + supplementary.repeat(25)}) {
            var request = new VehicleRequest("car", "AA:BB:CC:DD:EE:FF", type);
            when(transactions.put(user, request)).thenReturn(new VehicleResponse(new VehicleResponse.Details(UUID.randomUUID(), request.name(), request.bluetoothAddress(), type)));
            mvc.perform(put("/api/v1/vehicle").header("Authorization", bearer).contentType("application/json")
                    .content(body(request.name(), request.bluetoothAddress(), "\"" + type + "\"")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.vehicle.deviceType").value(type));
            verify(transactions).put(user, request);
        }
        clearInvocations(transactions);
        for (String type : new String[]{"a".repeat(51), supplementary.repeat(51), "가".repeat(25) + supplementary.repeat(26)}) {
            mvc.perform(put("/api/v1/vehicle").header("Authorization", bearer).contentType("application/json")
                    .content(body("car", "AA:BB:CC:DD:EE:FF", "\"" + type + "\"")))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        }
        verifyNoInteractions(transactions);
    }
    @Test void deviceTypeMayBeOmittedOrExplicitlyNull() throws Exception {
        var request = new VehicleRequest("car", "AA:BB:CC:DD:EE:FF", null);
        when(transactions.put(user, request)).thenReturn(new VehicleResponse(new VehicleResponse.Details(UUID.randomUUID(), request.name(), request.bluetoothAddress(), null)));
        for (String json : new String[]{"{\"name\":\"car\",\"bluetoothAddress\":\"AA:BB:CC:DD:EE:FF\"}", body("car", request.bluetoothAddress(), "null")}) {
            mvc.perform(put("/api/v1/vehicle").header("Authorization", bearer).contentType("application/json").content(json))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.vehicle.deviceType").hasJsonPath())
                    .andExpect(jsonPath("$.data.vehicle.deviceType").value(org.hamcrest.Matchers.nullValue()));
        }
        verify(transactions, times(2)).put(user, request);
    }
    @Test void acceptsSchemaBoundariesAndPreservesWhitespaceAndOptionalType() throws Exception {
        for (String type : new String[]{null, "", "a".repeat(50)}) {
            var request = new VehicleRequest(" " + "a".repeat(98) + " ", "AA:BB:CC:DD:EE:FF", type);
            when(transactions.put(user, request)).thenReturn(new VehicleResponse(new VehicleResponse.Details(UUID.randomUUID(), request.name(), request.bluetoothAddress(), type)));
            mvc.perform(put("/api/v1/vehicle").header("Authorization", bearer).contentType("application/json")
                    .content(body(request.name(), request.bluetoothAddress(), type == null ? "null" : "\"" + type + "\"")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.vehicle.name").value(request.name()));
            verify(transactions).put(user, request);
        }
    }
    @Test void translatesOnlyNamedUniqueViolationAndSanitizesOtherIntegrityFailures() throws Exception {
        var request = new VehicleRequest("car", "AA:BB:CC:DD:EE:FF", null);
        for (String constraint : new String[]{"uq_vehicles_active_user", "vehicles_pkey"}) {
            var sql = new java.sql.SQLException("private values", "23505");
            doThrow(new org.springframework.dao.DataIntegrityViolationException("private values",
                    new org.hibernate.exception.ConstraintViolationException("private values", sql, constraint))).when(transactions).put(user, request);
            mvc.perform(put("/api/v1/vehicle").header("Authorization", bearer).contentType("application/json").content(body("car", request.bluetoothAddress(), "null")))
                    .andExpect(status().is(constraint.equals("uq_vehicles_active_user") ? 409 : 500))
                    .andExpect(jsonPath("$.error.code").value(constraint.equals("uq_vehicles_active_user") ? "VEHICLE_CONFLICT" : "INTERNAL_SERVER_ERROR"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private values"))));
        }
    }
}
