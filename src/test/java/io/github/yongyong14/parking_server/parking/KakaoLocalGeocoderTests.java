package io.github.yongyong14.parking_server.parking;

import java.math.BigDecimal;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class KakaoLocalGeocoderTests {
    final tools.jackson.databind.ObjectMapper mapper=tools.jackson.databind.json.JsonMapper.builder().build();
    @Test void missingKeyMakesNoExternalRequest() throws Exception {
        var http=mock(HttpClient.class); var geocoder=new KakaoLocalGeocoder("",mapper,http);
        assertThat(geocoder.enabled()).isFalse();
        assertThat(geocoder.address(BigDecimal.ZERO,BigDecimal.ZERO)).isEmpty();
        verifyNoInteractions(http);
    }
    @Test void kakaoContractUsesLongitudeXAndLatitudeYAndParsesNullableAddress() throws Exception {
        var http=mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response=mock(HttpResponse.class);
        when(http.send(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"documents\":[{\"address\":{\"address_name\":\"테스트 지번 주소\"},\"road_address\":null}]}");
        var geocoder=new KakaoLocalGeocoder("test-only-key",mapper,http);
        assertThat(geocoder.address(new BigDecimal("37.32445"),new BigDecimal("127.096184"))).contains("테스트 지번 주소");
        var captured=ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(captured.capture(),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        assertThat(captured.getValue().uri().getHost()).isEqualTo("dapi.kakao.com");
        assertThat(captured.getValue().uri().getRawQuery()).contains("x=127.096184","y=37.32445","input_coord=WGS84");
        assertThat(captured.getValue().headers().firstValue("Authorization")).contains("KakaoAK test-only-key");
        when(response.body()).thenReturn("{\"documents\":[{\"address\":null,\"road_address\":{\"address_name\":\"테스트 도로명 주소\"}}]}");
        assertThat(geocoder.address(BigDecimal.ZERO,BigDecimal.ZERO)).contains("테스트 도로명 주소");
        when(response.statusCode()).thenReturn(401);
        assertThat(geocoder.address(BigDecimal.ZERO,BigDecimal.ZERO)).isEmpty();
    }
    @Test void queueRejectionAndWorkerFailureDoNotEscapeCommittedCallback() {
        var tx=mock(ParkingGeocodingTransactions.class);
        var event=new ParkingTransactions.Created(java.util.UUID.randomUUID(),java.util.UUID.randomUUID());
        var rejected=new ParkingGeocodingListener(task->{throw new java.util.concurrent.RejectedExecutionException();},tx);
        assertThatCode(()->rejected.committed(event)).doesNotThrowAnyException();
        doThrow(new IllegalStateException("private")).when(tx).fillAddress(event);
        var failed=new ParkingGeocodingListener(Runnable::run,tx);
        assertThatCode(()->failed.committed(event)).doesNotThrowAnyException();
    }
}
