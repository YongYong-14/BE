package io.github.yongyong14.parking_server.global;

import io.github.yongyong14.parking_server.global.exception.BusinessException;
import io.github.yongyong14.parking_server.global.exception.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.*;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.jackson.default-property-inclusion=non_null")
@AutoConfigureMockMvc
@Import(CommonHttpResponseTests.ErrorTestController.class)
class CommonHttpResponseTests {
    @Autowired
    MockMvc mvc;

    @Test
    void healthIncludesExplicitNullEvenWhenGlobalNullExclusionIsEnabled() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"success":true,"data":{"status":"UP"},"error":null}
                        """))
                .andExpect(jsonPath("$", aMapWithSize(3)))
                .andExpect(jsonPath("$", hasKey("error")))
                .andExpect(jsonPath("$.error", nullValue()));
    }

    @Test
    void validationFailure() throws Exception {
        failure(mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}")), 400, "INVALID_INPUT");
    }

    @Test
    void malformedJson() throws Exception {
        failure(mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON)
                .content("{")), 400, "INVALID_INPUT");
    }

    @Test
    void missingBody() throws Exception {
        failure(mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON)),
                400, "INVALID_INPUT");
    }

    @Test
    void typeMismatch() throws Exception {
        failure(mvc.perform(get("/test/parameter").param("number", "abc")), 400, "INVALID_INPUT");
    }

    @Test
    void missingRequiredParameter() throws Exception {
        failure(mvc.perform(get("/test/parameter")), 400, "INVALID_INPUT");
    }

    @Test
    void missingRequiredHeader() throws Exception {
        failure(mvc.perform(get("/test/header")), 400, "INVALID_INPUT");
    }

    @Test
    void missingRoute() throws Exception {
        failure(mvc.perform(get("/does-not-exist")), 404, "NOT_FOUND");
    }

    @Test
    void unsupportedMethodPreservesAllowHeader() throws Exception {
        failure(mvc.perform(post("/api/v1/health")), 405, "METHOD_NOT_ALLOWED")
                .andExpect(header().string("Allow", containsString("GET")));
    }

    @Test
    void unsupportedMediaTypeKeepsFrameworkStatus() throws Exception {
        failure(mvc.perform(post("/test/body").contentType(MediaType.TEXT_PLAIN).content("hello")),
                415, "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void unacceptableMediaTypeKeepsFrameworkStatus() throws Exception {
        failure(mvc.perform(get("/api/v1/health").accept(MediaType.APPLICATION_XML)),
                406, "NOT_ACCEPTABLE");
    }

    @Test
    void businessExceptionUsesDefaultMessage() throws Exception {
        failure(mvc.perform(get("/test/business-default")), 400, "INVALID_INPUT")
                .andExpect(jsonPath("$.error.message").value("입력값을 확인해 주세요."));
    }

    @Test
    void businessExceptionUsesSpecifiedStatusCodeAndMessage() throws Exception {
        failure(mvc.perform(get("/test/business")), 404, "NOT_FOUND")
                .andExpect(jsonPath("$.error.message").value("대상을 찾을 수 없습니다."));
    }

    @Test
    void unexpectedExceptionDoesNotExposeInternalDetails() throws Exception {
        failure(mvc.perform(get("/test/unexpected")), 500, "INTERNAL_SERVER_ERROR")
                .andExpect(jsonPath("$.error.message").value("서버 오류가 발생했습니다."))
                .andExpect(content().string(not(containsString("secret"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))))
                .andExpect(content().string(not(containsString("/internal"))));
    }

    private ResultActions failure(ResultActions result, int status, String code) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", aMapWithSize(3)))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$", hasKey("data")))
                .andExpect(jsonPath("$.data", nullValue()))
                .andExpect(jsonPath("$.error", aMapWithSize(2)))
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.error.message", not(emptyOrNullString())));
    }

    @RestController
    static class ErrorTestController {
        @PostMapping("/test/body")
        void body(@Valid @RequestBody Input input) { }

        @GetMapping("/test/parameter")
        void parameter(@RequestParam("number") int number) { }

        @GetMapping("/test/header")
        void header(@RequestHeader("X-Required") String value) { }

        @GetMapping("/test/business-default")
        void businessDefault() { throw new BusinessException(ErrorCode.INVALID_INPUT); }

        @GetMapping("/test/business")
        void business() { throw new BusinessException(ErrorCode.NOT_FOUND, "대상을 찾을 수 없습니다."); }

        @GetMapping("/test/unexpected")
        void unexpected() { throw new IllegalStateException("secret /internal/config.txt"); }

        record Input(@NotBlank String name) { }
    }
}
