package io.github.yongyong14.parking_server.global;

import io.github.yongyong14.parking_server.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(CommonHttpResponseTests.ErrorTestController.class)
class OpenApiTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void generatedDocumentMatchesRuntimeContract() throws Exception {
        JsonNode doc = mapper.readTree(mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(doc.path("openapi").asText()).startsWith("3.1.");
        assertThat(doc.at("/info/title").asText()).isEqualTo("용인 주차 안내 서비스 API");
        assertThat(doc.at("/info/version").asText()).isEqualTo("v1");
        assertThat(doc.has("success")).isFalse();
        assertThat(doc.path("paths").size()).isEqualTo(2);
        JsonNode operation = doc.path("paths").path("/api/v1/health").path("get");
        assertThat(operation.path("tags").get(0).asText()).isEqualTo("Health");
        JsonNode responses = operation.path("responses");
        assertThat(responses.size()).isEqualTo(4);
        JsonNode success = resolve(doc, responses.path("200").path("content").path("application/json").path("schema"));
        assertRequired(success, "success", "data", "error");
        assertThat(success.at("/properties/success/const").asBoolean()).isTrue();
        assertThat(success.at("/properties/error/type").asText()).isEqualTo("null");
        JsonNode data = success.at("/properties/data");
        assertThat(data.has("anyOf")).isFalse();
        assertThat(data.has("oneOf")).isFalse();
        assertThat(data.has("nullable")).isFalse();
        JsonNode health = resolve(doc, data);
        assertThat(health.path("type").asText()).isEqualTo("object");
        assertThat(health.at("/properties/status/type").asText()).isEqualTo("string");
        assertRequired(health, "status");
        for (String status : new String[]{"405", "406", "500"}) {
            JsonNode response = resolve(doc, responses.path(status));
            JsonNode media = response.path("content").path("application/json");
            JsonNode failure = resolve(doc, media.path("schema"));
            assertRequired(failure, "success", "data", "error");
            assertThat(failure.at("/properties/success/const").asBoolean()).isFalse();
            assertThat(failure.at("/properties/data/type").asText()).isEqualTo("null");
            JsonNode error = resolve(doc, failure.at("/properties/error"));
            assertRequired(error, "code", "message");
            assertThat(error.at("/properties/code/type").asText()).isEqualTo("string");
            assertThat(error.at("/properties/message/type").asText()).isEqualTo("string");
            ErrorCode code = ErrorCode.forStatus(Integer.parseInt(status));
            assertThat(media.at("/example/error/code").asText()).isEqualTo(code.getCode());
            assertThat(media.at("/example/error/message").asText()).isEqualTo(code.getMessage());
        }
        mvc.perform(post("/api/v1/health")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        assertThat(operation.path("security").isMissingNode() || operation.path("security").isEmpty()).isTrue();
        assertThat(doc.at("/components/securitySchemes/bearerAuth/scheme").asText()).isEqualTo("bearer");
        JsonNode healthActual = mapper.readTree(mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(healthActual).isEqualTo(responses.path("200").at("/content/application~1json/example"));
    }

    @Test
    void swaggerEntryConfigurationAndAssetsAreAccessible() throws Exception {
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/swagger-ui/index.html"));
        mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/v3/api-docs"))
                .andExpect(jsonPath("$.success").doesNotExist());
        for (String resource : new String[]{"index.html", "swagger-ui.css", "swagger-ui-bundle.js", "swagger-initializer.js"}) {
            mvc.perform(get("/swagger-ui/" + resource)).andExpect(status().isOk());
        }
    }

    private JsonNode resolve(JsonNode doc, JsonNode node) {
        return node.has("$ref") ? doc.at(node.path("$ref").asText().substring(1)) : node;
    }

    private void assertRequired(JsonNode schema, String... fields) {
        var names = new java.util.ArrayList<String>();
        schema.path("required").forEach(field -> names.add(field.asText()));
        assertThat(names).containsExactlyInAnyOrder(fields);
    }
}
