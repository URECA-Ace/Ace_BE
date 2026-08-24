package com.ace.coupon.persistence.relay;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands.StreamDeletionPolicy;
import org.springframework.data.redis.connection.RedisStreamCommands.TrimOptions;
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions;
import org.springframework.data.redis.connection.RedisStreamCommands.XTrimOptions;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.ace.coupon.redis.CouponIssueCompensationResult;
import com.ace.coupon.redis.CouponRedisKeys;

import lombok.extern.slf4j.Slf4j;
import com.ace.coupon.persistence.CouponIssuePersistenceProperties;
import com.ace.coupon.persistence.IssuePersistenceCoordinator;
import com.ace.coupon.persistence.IssuePersistenceService;
import com.ace.coupon.persistence.IssueRecord;
import com.ace.coupon.persistence.failure.IssueFailureStage;

// Stream 소비 계층(2차 저장 경로)
@Slf4j
@Component
@ConditionalOnProperty(prefix = "coupon.issue.persistence", name = "mode", havingValue = "RELAY")
public class IssueStreamRelay implements SmartLifecycle {

	// 0 부터
	private static final ReadOffset GROUP_START = ReadOffset.from("0");

	private static final String BUSY_GROUP = "BUSYGROUP";

	private final StringRedisTemplate redisTemplate;
	private final IssuePersistenceService persistenceService;
	private final IssuePersistenceCoordinator coordinator;
	private final RelayTargetProvider targetProvider;
	private final CouponIssuePersistenceProperties properties;

	private final AtomicBoolean running = new AtomicBoolean(false);
	private final String consumerName = resolveConsumerName();
	private Thread worker;

	public IssueStreamRelay(
			StringRedisTemplate redisTemplate,
			IssuePersistenceService persistenceService,
			IssuePersistenceCoordinator coordinator,
			RelayTargetProvider targetProvider,
			CouponIssuePersistenceProperties properties) {
		this.redisTemplate = redisTemplate;
		this.persistenceService = persistenceService;
		this.coordinator = coordinator;
		this.targetProvider = targetProvider;
		this.properties = properties;
	}

	// 인스턴스가 2대라 컨슈머 이름이 겹치면 서로의 pending 을 가져간다
	private static String resolveConsumerName() {
		String host;
		try {
			host = InetAddress.getLocalHost().getHostName();
		} catch (UnknownHostException exception) {
			host = "unknown";
		}
		return host + "-" + ProcessHandle.current().pid();
	}

	@Override
	public void start() {
		if (!running.compareAndSet(false, true)) {
			return;
		}
		worker = new Thread(this::loop, "issue-stream-relay");
		worker.setDaemon(true);
		worker.start();
		log.info("발급 Stream 릴레이 시작: consumer={}, group={}",
				consumerName, properties.consumerGroup());
	}

	@Override
	public void stop() {
		if (!running.compareAndSet(true, false)) {
			return;
		}
		if (worker != null) {
			worker.interrupt();
		}
		log.info("발급 Stream 릴레이 중지: consumer={}", consumerName);
	}

	@Override
	public boolean isRunning() {
		return running.get();
	}

	private void loop() {
		while (running.get()) {
			try {
				if (!runOnce()) {
					sleep(properties.refreshInterval());
				}
			} catch (InterruptedRelayException exception) {
				Thread.currentThread().interrupt();
				return;
			} catch (RuntimeException exception) {
				log.error("발급 Stream 릴레이 주기 실패", exception);
				sleep(properties.refreshInterval());
			}
		}
	}

	// 한 주기: 대상 갱신 -> 그룹 확인 -> 유휴 pending 회수 -> 신규 소비 -> 안전한 엔트리 정리
	boolean runOnce() {
		List<Long> campaignIds = targetProvider.campaignIds();
		if (campaignIds.isEmpty()) {
			return false;
		}

		List<String> keys = campaignIds.stream()
				.map(id -> CouponRedisKeys.campaign(id).issueStream())
				.toList();
		keys.forEach(this::ensureGroup);
		keys.forEach(this::reclaimPending);
		consume(keys);
		keys.forEach(this::trimAcknowledged);
		return true;
	}

	private StreamOperations<String, String, String> streams() {
		return redisTemplate.opsForStream();
	}

	// 이미 있으면 BUSYGROUP
	private void ensureGroup(String key) {
		try {
			streams().createGroup(key, GROUP_START, properties.consumerGroup());
		} catch (DataAccessException exception) {
			if (!isBusyGroup(exception)) {
				log.warn("Consumer Group 생성 실패: key={}", key, exception);
			}
		}
	}

	private boolean isBusyGroup(Throwable throwable) {
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current.getMessage() != null && current.getMessage().contains(BUSY_GROUP)) {
				return true;
			}
		}
		return false;
	}

	// 아직 아무도 안 읽은 신규 엔트리를 가져온다
	private void consume(List<String> keys) {
		StreamOffset<String>[] offsets = keys.stream()
				.map(key -> StreamOffset.create(key, ReadOffset.lastConsumed()))
				.toArray(StreamOffset[]::new);

		List<MapRecord<String, String, String>> records = streams().read(
				Consumer.from(properties.consumerGroup(), consumerName),
				StreamReadOptions.empty()
						.count(properties.batchSize())
						.block(properties.blockTimeout()),
				offsets);

		if (records == null) {
			return;
		}
		for (MapRecord<String, String, String> record : records) {
			process(record.getStream(), record, 1L);
		}
	}

	// 죽은 컨슈머 몫을 회수
	private void reclaimPending(String key) {
		PendingMessages pending = streams().pending(
				key, properties.consumerGroup(), Range.unbounded(), properties.batchSize());
		if (pending == null || pending.isEmpty()) {
			return;
		}

		for (PendingMessage message : pending) {
			if (message.getElapsedTimeSinceLastDelivery().compareTo(properties.claimMinIdle()) < 0) {
				continue;
			}
			List<MapRecord<String, String, String>> claimed = streams().claim(
					key,
					properties.consumerGroup(),
					consumerName,
					XClaimOptions.minIdle(properties.claimMinIdle()).ids(message.getId()));

			for (MapRecord<String, String, String> record : claimed) {
				// XCLAIM 이 전달 횟수 + 1
				// 조회값은 증가 전이라 +1 해야 이번이 몇 번째 전달인지 맞는다
				process(key, record, message.getTotalDeliveryCount() + 1);
			}
		}
	}

	// 저장 성공이면 XACK, 실패면 XACK 하지 않아 pending 에 남긴다
	private void process(String key, MapRecord<String, String, String> record, long deliveryCount) {
		Optional<IssueRecord> parsed;
		try {
			parsed = IssueRecord.fromStreamEntry(record.getValue(), record.getId().getValue());
		} catch (IllegalArgumentException exception) {
			log.error("Stream 엔트리 형식 오류로 건너뜁니다: key={}, id={}",
					key, record.getId(), exception);
			acknowledge(key, record.getId());
			return;
		}

		if (parsed.isEmpty()) {
			// 보상 엔트리는 저장 대상이 아니다
			acknowledge(key, record.getId());
			return;
		}

		IssueRecord issueRecord = parsed.get();
		try {
			persistenceService.persist(issueRecord);
			// 저장 실패에 보상하면 안 되므로 coordinator.persist() 대신 확정만 따로 부른다
			coordinator.confirmPersisted(issueRecord, record.getId().getValue());
			acknowledge(key, record.getId());
		} catch (RuntimeException exception) {
			handleFailure(key, record.getId(), issueRecord, deliveryCount, exception);
		}
	}

	// 한도 전에는 원복 X
	private void handleFailure(
			String key,
			RecordId recordId,
			IssueRecord issueRecord,
			long deliveryCount,
			RuntimeException exception) {

		if (deliveryCount < properties.maxDeliveryAttempts()) {
			log.warn("발급 저장 실패, 재처리 예정: requestId={}, delivery={}/{}",
					issueRecord.requestId(), deliveryCount, properties.maxDeliveryAttempts(), exception);
			return;
		}

		String incidentId = UUID.randomUUID().toString();
		log.error("발급 저장 재시도 한도 초과, 원복합니다: requestId={}, incidentId={}",
				issueRecord.requestId(), incidentId, exception);

		CouponIssueCompensationResult compensation =
				coordinator.abandon(issueRecord, IssueFailureStage.RELAY, incidentId, exception);

		// 원복 결과가 불확실하면 XACK 하지 않는다
		// 여기서 지우면 저장도 원복도 안 된 채 재처리 수단만 사라진다
		if (compensation == null) {
			log.error("원복 결과가 불확실해 pending 을 유지합니다: requestId={}, incidentId={}",
					issueRecord.requestId(), incidentId);
			return;
		}
		acknowledge(key, recordId);
	}

	private void acknowledge(String key, RecordId recordId) {
		streams().acknowledge(key, properties.consumerGroup(), recordId);
	}

	/**
	 * 모든 Consumer Group에서 처리 완료된 엔트리만 제거한다.
	 *
	 * <p>Redis 8.2의 {@code XTRIM MINID ... ACKED}를 사용하므로 현재 그룹의 pending뿐 아니라
	 * 다른 그룹이 아직 전달받지 않았거나 ACK하지 않은 엔트리도 삭제되지 않는다. 마지막 엔트리는
	 * MINID의 배타 경계로 남겨 Stream과 Consumer Group 자체가 유지되도록 한다.</p>
	 */
	private void trimAcknowledged(String key) {
		try {
			String lastGeneratedId = streams().info(key).lastGeneratedId();
			if (lastGeneratedId == null || "0-0".equals(lastGeneratedId)) {
				return;
			}

			Long trimmed = streams().trim(
					key,
					XTrimOptions.trim(
							TrimOptions.minId(RecordId.of(lastGeneratedId))
									.exact()
									.deletionPolicy(StreamDeletionPolicy.removeAcknowledged())));
			if (trimmed != null && trimmed > 0) {
				log.debug("처리 완료된 Stream 엔트리를 정리했습니다: key={}, count={}, minId={}",
						key, trimmed, lastGeneratedId);
			}
		} catch (DataAccessException | IllegalArgumentException exception) {
			// 정리는 저장 정확성보다 후순위다. 실패해도 다음 소비 주기에서 다시 시도한다.
			log.warn("처리 완료된 Stream 엔트리 정리 실패: key={}", key, exception);
		}
	}

	private void sleep(java.time.Duration duration) {
		try {
			Thread.sleep(duration.toMillis());
		} catch (InterruptedException exception) {
			throw new InterruptedRelayException(exception);
		}
	}

	private static class InterruptedRelayException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		InterruptedRelayException(Throwable cause) {
			super(cause);
		}
	}
}
