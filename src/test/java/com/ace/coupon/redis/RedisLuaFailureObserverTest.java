package com.ace.coupon.redis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class RedisLuaFailureObserverTest {

	@Test
	@DisplayName("Lua 실패 단계와 명령을 저카디널리티 메트릭 태그로 기록한다")
	void recordsFailureMetricByStageAndCommand() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		RedisLuaFailureObserver observer = new RedisLuaFailureObserver(registry);

		observer.observe(
				RedisLuaDiagnosticStage.ISSUE_STREAM_WRITE,
				"INTERNAL_WRITE_ERROR",
				"ERR XADD failed\nwith detail");

		double count = registry.get("coupon.redis.lua.failures")
				.tag("script", "issue")
				.tag("stage", "ISSUE_STREAM_WRITE")
				.tag("command", "XADD")
				.tag("result", "INTERNAL_WRITE_ERROR")
				.counter()
				.count();
		assertThat(count).isEqualTo(1.0);
	}

	@Test
	@DisplayName("진단 단계가 없는 비즈니스 결과는 실패 메트릭에 포함하지 않는다")
	void ignoresBusinessResultWithoutDiagnosticStage() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		RedisLuaFailureObserver observer = new RedisLuaFailureObserver(registry);

		observer.observe(RedisLuaDiagnosticStage.NONE, "SOLD_OUT", "");

		assertThat(registry.find("coupon.redis.lua.failures").counter()).isNull();
	}
}
