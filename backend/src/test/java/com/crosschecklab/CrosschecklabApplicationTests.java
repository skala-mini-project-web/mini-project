package com.crosschecklab;

import com.crosschecklab.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CrosschecklabApplicationTests extends IntegrationTestSupport {

	@Test
	@DisplayName("Flyway 마이그레이션 적용 후 애플리케이션 컨텍스트가 로드된다")
	void contextLoads() {
	}

}
