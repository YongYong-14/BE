package io.github.yongyong14.parking_server;

import io.github.yongyong14.parking_server.account.MockAccountRepositories;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@MockAccountRepositories
@SpringBootTest
class ParkingServerApplicationTests {

	@Test
	void contextLoads() {
	}

}
