package com.ace.consistency.schedule;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 스케줄러별 주기/다음 실행 시각/진행 여부를 Redis에 저장한다.
 * 멀티 인스턴스 배포 환경이라 이 값들은 특정 JVM의 로컬 상태가 아니라 인스턴스 전체가 공유하는
 * 값이어야 한다 (com.ace.notify.sse의 SSE 알림 전파와 같은 이유).
 */
@Component
@RequiredArgsConstructor
class ConsistencyScheduleStore {

	private static final String KEY_PREFIX = "consistency:schedule:";
	private static final String INTERVAL_FIELD = "intervalMs";
	private static final String NEXT_RUN_AT_FIELD = "nextRunAtEpochMs";
	// running 플래그의 안전망 TTL. 실행 도중 인스턴스가 죽어도 "진행중"이 영원히 남지 않게 한다.
	private static final Duration RUNNING_TTL = Duration.ofMinutes(30);

	private final StringRedisTemplate redisTemplate;

	long intervalMs(String schedulerName, long defaultMs) {
		Object value = redisTemplate.opsForHash().get(key(schedulerName), INTERVAL_FIELD);
		return value == null ? defaultMs : Long.parseLong(value.toString());
	}

	void saveIntervalMs(String schedulerName, long intervalMs) {
		redisTemplate.opsForHash().put(key(schedulerName), INTERVAL_FIELD, String.valueOf(intervalMs));
	}

	Long nextRunAtEpochMs(String schedulerName) {
		Object value = redisTemplate.opsForHash().get(key(schedulerName), NEXT_RUN_AT_FIELD);
		return value == null ? null : Long.parseLong(value.toString());
	}

	void saveNextRunAtEpochMs(String schedulerName, long epochMs) {
		redisTemplate.opsForHash().put(key(schedulerName), NEXT_RUN_AT_FIELD, String.valueOf(epochMs));
	}

	void markRunning(String schedulerName) {
		redisTemplate.opsForValue().set(runningKey(schedulerName), "1", RUNNING_TTL);
	}

	void markIdle(String schedulerName) {
		redisTemplate.delete(runningKey(schedulerName));
	}

	boolean isRunning(String schedulerName) {
		return Boolean.TRUE.equals(redisTemplate.hasKey(runningKey(schedulerName)));
	}

	private String key(String schedulerName) {
		return KEY_PREFIX + schedulerName;
	}

	private String runningKey(String schedulerName) {
		return KEY_PREFIX + schedulerName + ":running";
	}
}
