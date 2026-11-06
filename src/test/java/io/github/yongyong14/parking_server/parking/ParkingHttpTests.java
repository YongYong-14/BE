package io.github.yongyong14.parking_server.parking;

import java.sql.Timestamp;
import java.time.*;
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
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@AutoConfigureMockMvc
class ParkingHttpTests {
    @Autowired MockMvc mvc;
    @Autowired AccessTokenService tokens;
    @Autowired tools.jackson.databind.ObjectMapper mapper;
    @MockitoBean UserExistence users;
    @MockitoBean ParkingTransactions transactions;
    UUID user=UUID.randomUUID(),event=UUID.randomUUID();
    String bearer;
    final String body="""
            {"clientEventId":"00000000-0000-0000-0000-000000000001","vehicleId":"00000000-0000-0000-0000-000000000002",
             "detectedAt":"2026-11-07T19:05:12+09:00","confirmedAt":"2026-11-07T19:05:40+09:00",
             "confirmationMethod":"CONFIRMED","confirmedLocation":{"lat":37.324450,"lng":127.096184},"remeasureCount":0}
            """;
    @BeforeEach void setup() { when(users.exists(user)).thenReturn(true); bearer="Bearer "+tokens.issue(user).accessToken(); }
    ParkingTransactions.Result result(boolean created,String status) {
        var a=new HashMap<String,Object>();
        a.put("status",status); a.put("level","SUCCEEDED".equals(status) ? 2 : null);
        a.put("prediction_status","SUCCEEDED".equals(status) ? "OK" : "MISSING_ROW");
        a.put("attempt_count",1); a.put("notify_decision","SUCCEEDED".equals(status) ? "SEND" : "NONE");
        a.put("evaluated_at",Timestamp.from(Instant.parse("2026-11-07T10:05:41Z")));
        a.put("top_percent","SUCCEEDED".equals(status) ? 1.23456789 : null);
        a.put("reference_fine_amount","SUCCEEDED".equals(status) ? 47321 : null);
        a.put("message_title","SUCCEEDED".equals(status) ? "여기는 신고가 잦은 곳이에요" : null);
        return new ParkingTransactions.Result(event,created,a,List.of(),Map.of("detected_at",Timestamp.from(Instant.parse("2026-11-07T10:05:12Z"))));
    }
    @Test void unauthorizedAndRequiredFieldsNeverReachTransaction() throws Exception {
        mvc.perform(post("/api/v1/parking-events").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mvc.perform(post("/api/v1/parking-events").header("Authorization",bearer).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        verifyNoInteractions(transactions);
    }
    @Test void createdAndExistingReturnCompleteCommonResponseWithNullFields() throws Exception {
        when(transactions.save(eq(user),any())).thenReturn(result(true,"SUCCEEDED"),result(false,"SUCCEEDED"));
        for (int code:new int[]{201,200}) {
            var json=mapper.readTree(mvc.perform(post("/api/v1/parking-events").header("Authorization",bearer)
                    .param("userId",UUID.randomUUID().toString()).contentType("application/json").content(body))
                    .andExpect(status().is(code)).andExpect(jsonPath("$.success").value(true)).andReturn().getResponse().getContentAsString());
            assertThat(json.path("error").isNull()).isTrue();
            assertThat(json.at("/data/address").isNull()).isTrue();
            assertThat(json.at("/data/assessment/primaryZoneType").isNull()).isTrue();
            assertThat(json.at("/data/assessment/referenceFineAmount").asInt()).isEqualTo(47321);
            assertThat(json.at("/data/assessment/notify/expiresAt").asText()).isEqualTo("2026-11-07T19:35:12+09:00");
            assertThat(json.at("/data/assessment/attemptCount").asInt()).isEqualTo(1);
            assertThat(json.at("/data/assessment/evaluatedAt").asText()).isEqualTo("2026-11-07T19:05:41+09:00");
            for (String field:List.of("reportCount","reportRadiusM","periodStart","periodEnd","predictedCount"))
                assertThat(json.at("/data/assessment/stats").has(field)).isTrue();
            assertThat(json.at("/data/assessment/stats/topPercent").asDouble()).isEqualTo(1.23456789);
        }
        verify(transactions,times(2)).save(eq(user),any());
    }
    @Test void failedAssessmentIsSuccessfulDataAndKnownDomainErrorsUseTheirStatus() throws Exception {
        when(transactions.save(eq(user),any())).thenReturn(result(true,"FAILED"));
        mvc.perform(post("/api/v1/parking-events").header("Authorization",bearer).contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.assessment.status").value("FAILED"))
                .andExpect(jsonPath("$.data.assessment.level").value(org.hamcrest.Matchers.nullValue()));
        for (ErrorCode code:new ErrorCode[]{ErrorCode.LOCATION_CONSENT_REQUIRED,ErrorCode.VEHICLE_NOT_FOUND,ErrorCode.IDEMPOTENCY_KEY_REUSED,ErrorCode.INVALID_TIME,ErrorCode.ADJUST_DISTANCE_EXCEEDED}) {
            doThrow(new BusinessException(code)).when(transactions).save(eq(user),any());
            mvc.perform(post("/api/v1/parking-events").header("Authorization",bearer).contentType("application/json").content(body))
                    .andExpect(status().is(code.getStatus().value())).andExpect(jsonPath("$.error.code").value(code.getCode()))
                    .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
        }
    }
    @Test void rejectsMalformedUnsafeNumbersFractionalCountAndUnknownUserId() throws Exception {
        for (String bad:new String[]{body.replace("37.324450","NaN"),body.replace("37.324450","Infinity"),
                body.replace("37.324450","91"),body.replace("\"remeasureCount\":0","\"remeasureCount\":0.5"),
                body.replace("2026-11-07T19:05:12+09:00","2026-11-07T19:05:12"),
                body.replace("\"clientEventId\"","\"userId\":\"spoofed\",\"clientEventId\""),
                body.replace("\"CONFIRMED\"","\"AUTO\"")}) {
            mvc.perform(post("/api/v1/parking-events").header("Authorization",bearer).contentType("application/json").content(bad))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(transactions);
    }
    @Test void expectedConstraintMappingsAreExactAndUnexpectedDbErrorsStay500() throws Exception {
        for (String[] mapping:new String[][]{{"23514","ck_pe_time_order","INVALID_TIME_ORDER","400"},
                {"23514","ck_pe_adjust_200m","ADJUST_DISTANCE_EXCEEDED","400"},
                {"23514","ck_pe_no_auto_without_gps","AUTO_CONFIRM_NOT_ALLOWED","400"},
                {"23503","parking_events_vehicle_id_user_id_fkey","VEHICLE_NOT_FOUND","404"},
                {"23503","some_other_fk","INTERNAL_SERVER_ERROR","500"}}) {
            var message=new org.postgresql.util.ServerErrorMessage("SERROR\0C"+mapping[0]+"\0n"+mapping[1]+"\0Mprivate detail\0\0");
            var pg=new org.postgresql.util.PSQLException(message);
            doThrow(new org.springframework.dao.DataIntegrityViolationException("private",pg)).when(transactions).save(eq(user),any());
            mvc.perform(post("/api/v1/parking-events").header("Authorization",bearer).contentType("application/json").content(body))
                    .andExpect(status().is(Integer.parseInt(mapping[3]))).andExpect(jsonPath("$.error.code").value(mapping[2]))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
        }
    }
    @Test void swaggerDescribesAuthenticationFullResponseAndNullableRequest() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/parking-events'].post.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/parking-events'].post.parameters").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/parking-events'].post.responses['201'].content['application/json'].schema.$ref").value("#/components/schemas/ParkingSuccess"))
                .andExpect(jsonPath("$.paths['/api/v1/parking-events'].post.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/parking-events'].post.responses['403'].$ref").value("#/components/responses/LOCATION_CONSENT_REQUIRED"))
                .andExpect(jsonPath("$.components.schemas.ParkingAssessment.required").value(org.hamcrest.Matchers.hasItems("primaryZoneType","referenceFineAmount","attemptCount","evaluatedAt","notify")))
                .andExpect(jsonPath("$.components.schemas.ParkingRequest.properties.originalLocation.oneOf[1].type").value("null"))
                .andExpect(jsonPath("$.components.schemas.ParkingRequest.additionalProperties").value(false));
    }
    @Test void configurationCodesAreLoggedWithoutPrivateMessagesOrCausesAndApiRemains500() throws Exception {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        String privateMarker="private-sql-bind-coordinate-token-password-provider-response";
        try {
            for (var code:ParkingConfigurationException.Code.values()) {
                var failure=new ParkingConfigurationException(code);
                failure.initCause(new IllegalStateException(privateMarker));
                doThrow(failure).when(transactions).save(eq(user),any());
                mvc.perform(post("/api/v1/parking-events").header("Authorization",bearer).contentType("application/json").content(body))
                        .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"))
                        .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(code.name()))));
                assertThat(appender.list).anySatisfy(event->{
                    assertThat(event.getLoggerName()).isEqualTo(ParkingService.class.getName());
                    assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.ERROR);
                    assertThat(event.getFormattedMessage()).isEqualTo("Parking configuration rejected: code="+code.name());
                    assertThat(event.getThrowableProxy()).isNull();
                });
            }
            doThrow(new IllegalStateException(privateMarker,new java.sql.SQLException(privateMarker))).when(transactions).save(eq(user),any());
            mvc.perform(post("/api/v1/parking-events").header("Authorization",bearer).contentType("application/json").content(body))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"));
            long diagnosticCount=appender.list.stream().filter(event->event.getLoggerName().equals(ParkingService.class.getName())).count();
            doReturn(result(true,"FAILED")).when(transactions).save(eq(user),any());
            mvc.perform(post("/api/v1/parking-events").header("Authorization",bearer).contentType("application/json").content(body))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.assessment.status").value("FAILED"));
            assertThat(appender.list.stream().filter(event->event.getLoggerName().equals(ParkingService.class.getName())).count()).isEqualTo(diagnosticCount);
            for (var event:appender.list) {
                assertThat(event.getFormattedMessage()).doesNotContain(privateMarker,bearer,bearer.substring(7),body,"37.324450","127.096184");
                if(event.getThrowableProxy()!=null)
                    assertThat(ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(event.getThrowableProxy()))
                            .doesNotContain(privateMarker,bearer,bearer.substring(7),body,"37.324450","127.096184");
            }
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}
