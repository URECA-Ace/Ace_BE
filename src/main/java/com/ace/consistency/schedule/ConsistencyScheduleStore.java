package com.ace.consistency.schedule;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;

/** 단일 애플리케이션 인스턴스에서 사용하는 스케줄러 실행 상태 저장소. */
@Component
class ConsistencyScheduleStore {

	private final Map<String, Long> intervals = new ConcurrentHashMap<>();
	private final Map<String, Long> nextRunAtEpochMs = new ConcurrentHashMap<>();
	private final Map<String, AtomicBoolean> running = new ConcurrentHashMap<>();

	long intervalMs(String schedulerName, long defaultMs) {
		return intervals.getOrDefault(schedulerName, defaultMs);
	}

	void saveIntervalMs(String schedulerName, long intervalMs) {
		intervals.put(schedulerName, intervalMs);
	}

	Long nextRunAtEpochMs(String schedulerName) {
		return nextRunAtEpochMs.get(schedulerName);
	}

	void saveNextRunAtEpochMs(String schedulerName, long epochMs) {
		nextRunAtEpochMs.put(schedulerName, epochMs);
	}

	void markRunning(String schedulerName) {
		running.computeIfAbsent(schedulerName, ignored -> new AtomicBoolean()).set(true);
	}

	void markIdle(String schedulerName) {
		running.computeIfAbsent(schedulerName, ignored -> new AtomicBoolean()).set(false);
	}

	boolean isRunning(String schedulerName) {
		AtomicBoolean state = running.get(schedulerName);
		return state != null && state.get();
	}
}
