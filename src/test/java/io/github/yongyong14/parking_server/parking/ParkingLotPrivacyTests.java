package io.github.yongyong14.parking_server.parking;

import io.github.yongyong14.parking_server.account.MockAccountRepositories;
import io.github.yongyong14.parking_server.global.exception.*;
import io.github.yongyong14.parking_server.global.security.*;
import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.*;
import ch.qos.logback.core.read.ListAppender;
import java.nio.file.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@MockAccountRepositories
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"logging.level.org.springframework=DEBUG","logging.level.io.github.yongyong14.parking_server.parking=DEBUG",
                "server.address=127.0.0.1","server.tomcat.accesslog.enabled=true","server.tomcat.accesslog.buffered=false"})
class ParkingLotPrivacyTests {
    static final Path ACCESS=Path.of("build/parking-lots-verification/privacy-"+UUID.randomUUID());
    @DynamicPropertySource static void accessLogDirectory(DynamicPropertyRegistry properties) {
        properties.add("server.tomcat.accesslog.directory",()->ACCESS.toAbsolutePath().toString());
    }
    @Value("${local.server.port}") int port;
    @Autowired AccessTokenService tokens;
    @MockitoBean UserExistence users;
    @MockitoBean ParkingLotService service;
    @Test void successfulRejectedAndFailedRequestsKeepCoordinatesOutOfDebugErrorAndAccessLogs() throws Exception {
        UUID user=UUID.randomUUID(); when(users.exists(user)).thenReturn(true);
        String token=tokens.issue(user).accessToken();
        var root=(Logger)LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var capture=new ListAppender<ILoggingEvent>(); capture.start(); root.addAppender(capture);
        try {
            assertThat(((Logger)LoggerFactory.getLogger("org.springframework.jdbc.core.StatementCreatorUtils")).getEffectiveLevel()).isEqualTo(Level.INFO);
            String query="lat=37.320123&lng=127.090123";
            String url="/api/v1/parking-lots/nearby?"+query;
            assertThat(request(url,null).statusCode()).isEqualTo(401);
            assertThat(request(url,"invalid-token-marker").statusCode()).isEqualTo(401);
            when(service.nearby(any(),anyDouble(),anyDouble(),anyInt())).thenReturn(new ParkingLotResponse(500,false,List.of(
                    new ParkingLotResponse.Item(UUID.randomUUID(),"fixture",null,0,new ParkingLotResponse.Location(37.320123,127.090123),null,null,null,null,null))));
            assertThat(request(url,token).statusCode()).isEqualTo(200);
            assertThat(request(url+"&radius=bad-radius",token).statusCode()).isEqualTo(400);
            assertThat(request(url.replace("37.320123","37.320123-invalid"),token).statusCode()).isEqualTo(400);
            doThrow(new BusinessException(ErrorCode.LOCATION_CONSENT_REQUIRED)).when(service).nearby(any(),anyDouble(),anyDouble(),anyInt());
            assertThat(request(url,token).statusCode()).isEqualTo(403);
            String sensitive=query+" Bearer "+token;
            doThrow(new org.springframework.dao.DataAccessResourceFailureException(sensitive)).when(service).nearby(any(),anyDouble(),anyDouble(),anyInt());
            var failed=request(url,token);
            assertThat(failed.statusCode()).isEqualTo(500);
            assertThat(failed.body()).doesNotContain(sensitive);
            String logs=capture.list.stream().map(e->e.getLoggerName()+" "+e.getFormattedMessage()+
                    (e.getThrowableProxy()==null?"":ThrowableProxyUtil.asString(e.getThrowableProxy())))
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertThat(logs).contains("Parking lot request failed","Parking lot request rejected")
                    .doesNotContain("37.320123","127.090123",query,token,"Bearer ","invalid-token-marker");
            String access="";
            for(int attempt=0;attempt<30;attempt++) {
                try(var files=Files.list(ACCESS)) {
                    access=files.filter(Files::isRegularFile).map(p->{try{return Files.readString(p);}catch(Exception e){throw new RuntimeException(e);}})
                            .collect(java.util.stream.Collectors.joining("\n"));
                }
                if(access.lines().count()>=7) break;
                Thread.sleep(100);
            }
            assertThat(access.lines().count()).isGreaterThanOrEqualTo(7);
            assertThat(access).contains("GET /api/v1/parking-lots/nearby HTTP/")
                    .doesNotContain("37.320123","127.090123","bad-radius",token,"Bearer ");
        } finally { root.detachAppender(capture); capture.stop(); }
    }
    private HttpResponse<String> request(String path,String token) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET();
        if(token!=null) builder.header("Authorization","Bearer "+token);
        try(var client=HttpClient.newHttpClient()) { return client.send(builder.build(),HttpResponse.BodyHandlers.ofString()); }
    }
}
