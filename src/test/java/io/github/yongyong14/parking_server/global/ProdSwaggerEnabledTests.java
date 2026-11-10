package io.github.yongyong14.parking_server.global;

import io.github.yongyong14.parking_server.account.MockAccountRepositories;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@MockAccountRepositories
@SpringBootTest(properties = "SWAGGER_ENABLED=true")
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class ProdSwaggerEnabledTests {
    @Autowired MockMvc mvc;

    @Test
    void productionCanExposeDocumentationGetRequests() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists());
        mvc.perform(get("/v3/api-docs.yaml")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk());
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
        for (String resource : new String[]{"index.html", "swagger-ui.css", "swagger-ui-bundle.js", "swagger-initializer.js"}) {
            mvc.perform(get("/swagger-ui/" + resource)).andExpect(status().isOk());
        }
    }

    @Test
    void enablingSwaggerKeepsOtherPathsAndDocumentationWritesProtected() throws Exception {
        for (String path : new String[]{"/api/v1/me", "/api/v1/vehicle", "/api/v1/notification-settings",
                "/v3/api-docs-other", "/swagger-ui-other"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        for (String path : new String[]{"/v3/api-docs", "/swagger-ui/index.html"}) {
            mvc.perform(post(path)).andExpect(status().isUnauthorized());
        }
    }
}
