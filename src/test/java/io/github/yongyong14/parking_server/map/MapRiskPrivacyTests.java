package io.github.yongyong14.parking_server.map;

import io.github.yongyong14.parking_server.account.MockAccountRepositories;
import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.*;
import ch.qos.logback.core.read.ListAppender;
import io.github.yongyong14.parking_server.global.exception.*;
import io.github.yongyong14.parking_server.global.security.*;
import java.nio.file.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@MockAccountRepositories
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"logging.level.org.springframework=DEBUG",
        "logging.level.io.github.yongyong14.parking_server.map=DEBUG",
        "server.address=127.0.0.1","server.tomcat.accesslog.enabled=true",
        "server.tomcat.accesslog.buffered=false"})
class MapRiskPrivacyTests {
    static final Path ACCESS=Path.of("build/map-verification/privacy-access-"+UUID.randomUUID());
    @DynamicPropertySource static void accessLogDirectory(DynamicPropertyRegistry properties) {
        properties.add("server.tomcat.accesslog.directory",()->ACCESS.toAbsolutePath().toString());
    }
    @Value("${local.server.port}") int port;
    @Autowired AccessTokenService tokens;
    @MockitoBean UserExistence users;
    @MockitoBean MapRiskService service;

    @Test void broadDebugAndFailuresNeverCaptureViewportOrTokens() throws Exception {
        UUID user=UUID.randomUUID(); when(users.exists(user)).thenReturn(true);
        String token=tokens.issue(user).accessToken();
        var root=(Logger)LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var capture=new ListAppender<ILoggingEvent>(); capture.start(); root.addAppender(capture);
        try {
            for(String logger:List.of("org.springframework.security.web.FilterChainProxy",
                    "org.springframework.web.servlet.DispatcherServlet",
                    "org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver"))
                assertThat(((Logger)LoggerFactory.getLogger(logger)).getEffectiveLevel()).isEqualTo(Level.INFO);
            String query="minLat=37.320123&minLng=127.090123&maxLat=37.321123&maxLng=127.091123";
            String url="/api/v1/map/risk-zones?"+query;
            assertThat(request(url,null).statusCode()).isEqualTo(401);
            assertThat(request(url,"invalid-token-marker").statusCode()).isEqualTo(401);
            // A returned facility can equal a requested corner; response diagnostics must not expose it either.
            when(service.get(any(),any())).thenReturn(new MapRiskResponse(
                    List.of(new MapRiskResponse.FacilityZone("HYDRANT",new MapRiskResponse.Location(37.320123,127.090123),5)),
                    List.of(),new MapRiskResponse.Slot(6,1),null));
            assertThat(request(url,token).statusCode()).isEqualTo(200);
            assertThat(request(url+"&at=bad-time",token).statusCode()).isEqualTo(400);
            assertThat(request(url.replace("37.320123","37.320123-invalid"),token).statusCode()).isEqualTo(400);
            String sensitive=query+" Bearer "+token;
            doThrow(new org.springframework.dao.DataAccessResourceFailureException(sensitive)).when(service).get(any(),any());
            var failed=request(url,token);
            assertThat(failed.statusCode()).isEqualTo(500);
            assertThat(failed.body()).contains("INTERNAL_SERVER_ERROR").doesNotContain(sensitive);
            doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,new IllegalStateException(sensitive)))
                    .when(service).get(any(),any());
            assertThat(request(url,token).statusCode()).isEqualTo(503);
            String logs=capture.list.stream().map(e->e.getLoggerName()+" "+e.getFormattedMessage()+
                    (e.getThrowableProxy()==null?"":ThrowableProxyUtil.asString(e.getThrowableProxy())))
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertThat(logs).contains("Map request failed", "Map request rejected")
                    .doesNotContain("37.320123","127.090123","37.321123","127.091123",query,token,"Bearer ","invalid-token-marker");
            String access="";
            for(int attempt=0;attempt<30;attempt++) {
                try(var files=Files.list(ACCESS)) {
                    access=files.filter(Files::isRegularFile).map(p->{try {return Files.readString(p);} catch(Exception e){throw new RuntimeException(e);}})
                            .collect(java.util.stream.Collectors.joining("\n"));
                }
                if(access.lines().count()>=7) break;
                Thread.sleep(100);
            }
            assertThat(access.lines().count()).isGreaterThanOrEqualTo(7);
            assertThat(access).contains("GET /api/v1/map/risk-zones HTTP/")
                    .doesNotContain("minLat","minLng","maxLat","maxLng","bad-time",token,"Bearer ");
            // Persist only after verifying the captured output contains no sentinels or credentials.
            Path output=Path.of("build/map-verification/privacy-capture.log");
            Files.createDirectories(output.getParent()); Files.writeString(output,logs);
        } finally { root.detachAppender(capture); capture.stop(); }
    }
    private HttpResponse<String> request(String path,String token) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET();
        if(token!=null) builder.header("Authorization","Bearer "+token);
        try(var client=HttpClient.newHttpClient()) { return client.send(builder.build(),HttpResponse.BodyHandlers.ofString()); }
    }
}
