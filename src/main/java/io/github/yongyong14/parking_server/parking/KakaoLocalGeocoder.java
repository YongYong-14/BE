package io.github.yongyong14.parking_server.parking;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Login-independent Local API client. Never log URI, key, response address or exception causes. */
@Component
public class KakaoLocalGeocoder implements ReverseGeocoder {
    private final String key;
    private final ObjectMapper mapper;
    private final HttpClient http;
    @org.springframework.beans.factory.annotation.Autowired
    public KakaoLocalGeocoder(@Value("${app.kakao.local.rest-api-key:}") String key, ObjectMapper mapper) {
        this(key,mapper,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }
    KakaoLocalGeocoder(String key,ObjectMapper mapper,HttpClient http) { this.key=key; this.mapper=mapper; this.http=http; }
    @Override public boolean enabled() { return key!=null && !key.isBlank(); }
    @Override public Optional<String> address(BigDecimal lat,BigDecimal lng) throws Exception {
        if (!enabled()) return Optional.empty();
        var request=HttpRequest.newBuilder(URI.create("https://dapi.kakao.com/v2/local/geo/coord2address.json?input_coord=WGS84&x="
                +lng.toPlainString()+"&y="+lat.toPlainString())).timeout(Duration.ofSeconds(2))
                .header("Authorization","KakaoAK "+key).GET().build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofString());
        if (response.statusCode()!=200) return Optional.empty();
        var documents=mapper.readTree(response.body()).path("documents");
        if (!documents.isArray() || documents.isEmpty()) return Optional.empty();
        // Prefer parcel address: this is the source used in the API example; road address may be absent.
        for (String field:new String[]{"address","road_address"}) {
            var value=documents.get(0).path(field).path("address_name");
            if (value.isTextual() && !value.asText().isBlank()) return Optional.of(value.asText());
        }
        return Optional.empty();
    }
    @Override public String toString() { return "KakaoLocalGeocoder[redacted]"; }
}
