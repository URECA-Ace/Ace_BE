package com.ace.consistency.schedule;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import com.ace.common.ErrorCode;
import com.ace.common.exception.ConsistencyCheckException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 정합성 스케줄러들(ALL/AS_OF_RANGE/고아 행 정리)이 재배포 없이, 그리고 여러 인스턴스에
 * 걸쳐 동시에 실행 주기를 바꿀 수 있게 해주는 중앙 코디네이터.
 *
 * {@code @Scheduled(fixedDelayString=...)}는 앱 기동 시 딱 한 번만 해석되어 재배포 없인 못
 * 바꾸므로, 각 스케줄러는 이 클래스에 자기 자신을 등록하고 {@link TaskScheduler#schedule}로
 * "실행 -> 다음 실행 시각 계산 -> 재예약"을 스스로 반복한다.
 *
 * 주기가 바뀌면(관리자가 API로 변경) "변경 시점부터 새 주기만큼 다시 기다린다" — 이미 예약된
 * 다음 실행을 취소하고, now + newIntervalMs로 다시 예약한다. 여러 인스턴스에 이 변경을
 * 동일하게 반영해야 하므로, 각 인스턴스는 로컬에 즉시 반영함과 동시에 Redis Pub/Sub로 전파해서
 * 다른 인스턴스들도 자신의 로컬 예약을 취소·재예약하게 한다(com.ace.notify.sse와 동일한 패턴).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsistencySchedulerCoordinator {

	private final TaskScheduler taskScheduler;
	private final ConsistencyScheduleStore store;
	private final StringRedisTemplate redisTemplate;
	@Qualifier("consistencyScheduleChangedTopic")
	private final ChannelTopic consistencyScheduleChangedTopic;

	private final Map<String, RegisteredTask> tasks = new ConcurrentHashMap<>();

	private record RegisteredTask(long defaultIntervalMs, Runnable runnable, AtomicReference<ScheduledFuture<?>> future) {
	}

	/** 스케줄러가 기동 시(@PostConstruct) 자기 자신을 등록한다. */
	public void register(String schedulerName, long defaultIntervalMs, Runnable runnable) {
		RegisteredTask task = new RegisteredTask(defaultIntervalMs, runnable, new AtomicReference<>());
		tasks.put(schedulerName, task);

		long intervalMs = store.intervalMs(schedulerName, defaultIntervalMs);
		Long storedNextRunAt = store.nextRunAtEpochMs(schedulerName);
		Instant nextRunAt = storedNextRunAt != null
				? Instant.ofEpochMilli(storedNextRunAt)
				: Instant.now().plusMillis(intervalMs);
		store.saveNextRunAtEpochMs(schedulerName, nextRunAt.toEpochMilli());
		scheduleAt(schedulerName, task, nextRunAt);
	}

	/** 관리자 API가 호출하는 진입점. 이 인스턴스에 즉시 반영하고, 나머지 인스턴스에는 Redis로 전파한다. */
	public void changeInterval(String schedulerName, long newIntervalMs) {
		if (!tasks.containsKey(schedulerName)) {
			throw new ConsistencyCheckException(ErrorCode.SCHEDULE_NOT_FOUND);
		}
		store.saveIntervalMs(schedulerName, newIntervalMs);
		applyIntervalChange(schedulerName, newIntervalMs);
		redisTemplate.convertAndSend(consistencyScheduleChangedTopic.getTopic(), schedulerName + ":" + newIntervalMs);
	}

	/**
	 * 실제 주기 변경 반영. changeInterval()의 로컬 적용과, 다른 인스턴스에서 온 Redis Pub/Sub
	 * 메시지(ConsistencyScheduleRedisSubscriber) 양쪽에서 호출된다.
	 */
	void applyIntervalChange(String schedulerName, long newIntervalMs) {
		RegisteredTask task = tasks.get(schedulerName);
		if (task == null) {
			// 이 인스턴스에서는 해당 스케줄러가 비활성화되어 있음(consistency.xxx.enabled=false)
			return;
		}
		Instant next = Instant.now().plusMillis(newIntervalMs);
		store.saveNextRunAtEpochMs(schedulerName, next.toEpochMilli());
		scheduleAt(schedulerName, task, next);
	}

	private void scheduleAt(String schedulerName, RegisteredTask task, Instant time) {
		ScheduledFuture<?> previous = task.future()
				.getAndSet(taskScheduler.schedule(() -> executeAndReschedule(schedulerName, task), time));
		if (previous != null) {
			previous.cancel(false);
		}
	}

	private void executeAndReschedule(String schedulerName, RegisteredTask task) {
		store.markRunning(schedulerName);
		try {
			task.runnable().run();
		} catch (Exception ex) {
			// 여기서 삼키지 않으면 재예약(finally 아래 로직)까지 못 가서 스케줄러가 완전히 멈춘다.
			// @Scheduled가 기본으로 해주던 "예외 로깅 후 다음 틱은 계속 진행"을 직접 구현한 것.
			log.error("스케줄러 실행 중 예외가 발생했습니다. schedulerName={}", schedulerName, ex);
		} finally {
			store.markIdle(schedulerName);
		}

		long intervalMs = store.intervalMs(schedulerName, task.defaultIntervalMs());
		Instant next = Instant.now().plusMillis(intervalMs);
		store.saveNextRunAtEpochMs(schedulerName, next.toEpochMilli());
		scheduleAt(schedulerName, task, next);
	}

	public List<ScheduleStatus> statuses() {
		return tasks.entrySet().stream()
				.map(entry -> toStatus(entry.getKey(), entry.getValue()))
				.toList();
	}

	public Optional<ScheduleStatus> status(String schedulerName) {
		RegisteredTask task = tasks.get(schedulerName);
		return task == null ? Optional.empty() : Optional.of(toStatus(schedulerName, task));
	}

	private ScheduleStatus toStatus(String schedulerName, RegisteredTask task) {
		long intervalMs = store.intervalMs(schedulerName, task.defaultIntervalMs());
		Long nextRunAt = store.nextRunAtEpochMs(schedulerName);
		return new ScheduleStatus(
				schedulerName,
				intervalMs,
				nextRunAt == null ? null : Instant.ofEpochMilli(nextRunAt),
				store.isRunning(schedulerName));
	}
}
