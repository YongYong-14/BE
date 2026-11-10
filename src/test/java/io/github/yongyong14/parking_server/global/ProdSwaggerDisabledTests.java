package io.github.yongyong14.parking_server.global;

import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "SWAGGER_ENABLED=false")
class ProdSwaggerDisabledTests extends ProdOpenApiTests {
}
