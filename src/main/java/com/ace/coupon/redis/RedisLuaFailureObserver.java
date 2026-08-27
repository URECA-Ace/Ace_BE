package com.ace.coupon.redis;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class RedisLuaFailureObserver {

	private static final int MAX_LOG_ERROR_LENGTH = 500;

	private final MeterRegistry meterRegistry;

	public RedisLuaFailureObserver(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	public void observe(
			RedisLuaDiagnosticStage stage,
			String result,
			String rawError) {
		if (stage == null || stage == RedisLuaDiagnosticStage.NONE) {
			return;
		}

		String message = sanitize(rawError);
		log.error("Redis Lua 명령 실패: script={}, stage={}, command={}, result={}, redisError={}",
				stage.script(), stage.name(), stage.command(), result, message);

		Counter.builder("coupon.redis.lua.failures")
				.description("Redis Lua pcall command failures")
				.tag("script", stage.script())
				.tag("stage", stage.name())
				.tag("command", stage.command())
				.tag("result", result)
				.register(meterRegistry)
				.increment();
	}

	private String sanitize(String rawError) {
		if (rawError == null || rawError.isBlank()) {
			return "no redis error detail";
		}
		String singleLine = rawError.replace('\r', ' ').replace('\n', ' ');
		return singleLine.length() <= MAX_LOG_ERROR_LENGTH
				? singleLine
				: singleLine.substring(0, MAX_LOG_ERROR_LENGTH);
	}
}
