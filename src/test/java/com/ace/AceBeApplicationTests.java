package com.ace;

import com.ace.coupon.service.CouponIssueService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class AceBeApplicationTests {

	// Redis + Lua 기반 실제 구현체가 추가되기 전까지 테스트 Context에서만 대체한다.
	@MockitoBean
	private CouponIssueService couponIssueService;

	@Test
	void contextLoads() {
	}

}
