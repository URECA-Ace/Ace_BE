package com.ace.coupon.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CouponEventStatsLuaCodeTest {

	@Test
	@DisplayName("발급 현황 Lua 결과 코드를 Enum으로 변환한다")
	void convertsLuaResultCode() {
		assertThat(CouponEventStatsLuaCode.from(0L))
				.isEqualTo(CouponEventStatsLuaCode.SUCCESS);
		assertThat(CouponEventStatsLuaCode.from(1L))
				.isEqualTo(CouponEventStatsLuaCode.CAMPAIGN_NOT_INITIALIZED);
		assertThat(CouponEventStatsLuaCode.from(2L))
				.isEqualTo(CouponEventStatsLuaCode.CORRUPTED_STATE);
	}

	@Test
	@DisplayName("정의되지 않은 발급 현황 Lua 결과 코드를 거절한다")
	void rejectsUnknownLuaResultCode() {
		assertThatThrownBy(() -> CouponEventStatsLuaCode.from(999L))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("정의되지 않은 쿠폰 현황 Lua 코드");
	}
}
