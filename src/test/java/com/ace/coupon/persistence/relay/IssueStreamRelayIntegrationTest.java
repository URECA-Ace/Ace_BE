package com.ace.coupon.persistence.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import com.ace.coupon.redis.CampaignRedisInitializer;
import com.ace.coupon.redis.CouponIssueCompensationResult;
import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.redis.CouponRedisKeys;
import com.ace.coupon.redis.RedisCouponIssueProcessor;
import com.ace.coupon.redis.RedisLuaFailureObserver;
import com.ace.coupon.persistence.CouponIssuePersistenceProperties;
import com.ace.coupon.persistence.IssuePersistenceCoordinator;
import com.ace.coupon.persistence.IssuePersistenceService;
import com.ace.coupon.persistence.IssueRecord;
import com.ace.coupon.persistence.PersistenceMode;
import com.ace.coupon.persistence.failure.IssueFailureStage;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

// Stream 소비 계층을 실제 Redis 에 대고 검증
@Tag("redis-integration")
class IssueStreamRelayIntegrationTest {

	private static final String GROUP = "issue-persist-test";
	private static final AtomicLong CAMPAIGN_ID =
			new AtomicLong(9_100_000_000_000_000L + System.currentTimeMillis());

	private static LettuceConnectionFactory connectionFactory;
	private static StringRedisTemplate redisTemplate;
	private static CampaignRedisInitializer initializer;
	private static RedisCouponIssueProcessor processor;

	private long campaignId;
	private String streamKey;
	private IssuePersistenceService persistenceService;
	private IssuePersistenceCoordinator coordinator;
	private RelayTargetProvider targetProvider;

	@BeforeAll
	static void setUpRedis() {
		String host = System.getProperty("redis.host", "localhost");
		int port = Integer.parseInt(System.getProperty("redis.port", "6379"));
		connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
		connectionFactory.afterPropertiesSet();
		connectionFactory.start();

		redisTemplate = new StringRedisTemplate(connectionFactory);
		redisTemplate.afterPropertiesSet();

		CouponIssueRedisProperties properties =
				new CouponIssueRedisProperties(Duration.ofMinutes(10), ZoneId.of("Asia/Seoul"));
		RedisLuaFailureObserver failureObserver =
				new RedisLuaFailureObserver(new SimpleMeterRegistry());
		initializer = new CampaignRedisInitializer(
				redisTemplate,
				script("scripts/coupon-campaign-initialize.lua", List.class),
				properties,
				failureObserver);
		processor = new RedisCouponIssueProcessor(
				redisTemplate,
				script("scripts/coupon-issue.lua", List.class),
				script("scripts/coupon-issue-compensate.lua", List.class),
				script("scripts/coupon-issue-confirm.lua", List.class),
				failureObserver);
	}

	@AfterAll
	static void tearDownRedis() {
		connectionFactory.destroy();
	}

	private static <T> RedisScript<T> script(String location, Class<T> type) {
		DefaultRedisScript<T> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource(location));
		script.setResultType(type);
		return script;
	}

	@BeforeEach
	void setUp() {
		campaignId = CAMPAIGN_ID.incrementAndGet();
		streamKey = CouponRedisKeys.campaign(campaignId).issueStream();
		Instant now = Instant.now();
		initializer.initialize(campaignId, 10, now.minusSeconds(60), now.plusSeconds(3600));

		persistenceService = mock(IssuePersistenceService.class);
		coordinator = mock(IssuePersistenceCoordinator.class);
		targetProvider = mock(RelayTargetProvider.class);
		given(targetProvider.campaignIds()).willReturn(List.of(campaignId));
	}

	@AfterEach
	void cleanUp() {
		redisTemplate.delete(redisTemplate.keys("coupon:{campaign:" + campaignId + "}:*"));
	}

	private IssueStreamRelay relay(int maxDeliveryAttempts, Duration claimMinIdle) {
		return new IssueStreamRelay(
				redisTemplate,
				persistenceService,
				coordinator,
				targetProvider,
				new CouponIssuePersistenceProperties(
						PersistenceMode.RELAY, GROUP, 100,
						Duration.ofMillis(50), claimMinIdle, maxDeliveryAttempts, Duration.ofSeconds(1)));
	}

	private UUID issue(long userId) {
		UUID requestId = UUID.randomUUID();
		processor.issue(campaignId, userId, requestId);
		return requestId;
	}

	private void sleepMillis(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
	}

	private long pendingCount() {
		PendingMessages pending = redisTemplate.<String, String>opsForStream()
				.pending(streamKey, GROUP, Range.unbounded(), 100);
		return pending == null ? 0 : pending.size();
	}

	@Test
	@DisplayName("승인 엔트리를 저장하고 XACK 한다")
	void persistsAndAcknowledges() {
		UUID requestId = issue(1L);

		assertThat(relay(3, Duration.ofSeconds(30)).runOnce()).isTrue();

		ArgumentCaptor<IssueRecord> captor = ArgumentCaptor.forClass(IssueRecord.class);
		verify(persistenceService).persist(captor.capture());
		IssueRecord record = captor.getValue();
		assertThat(record.requestId()).isEqualTo(requestId);
		assertThat(record.campaignId()).isEqualTo(campaignId);
		assertThat(record.userId()).isEqualTo(1L);
		assertThat(record.issueSequence()).isEqualTo(1L);
		// Stream 엔트리 식별자가 message_id 로 들어가 같은 엔트리의 중복 처리를 막는다
		assertThat(record.messageId()).isNotBlank();

		// 저장 -> 확정 -> XACK 순서. 확정 배선이 빠지면 여기서 걸린다
		InOrder inOrder = inOrder(persistenceService, coordinator);
		inOrder.verify(persistenceService).persist(any());
		inOrder.verify(coordinator).confirmPersisted(any(), any());
		assertThat(pendingCount()).isZero();
	}

	@Test
	@DisplayName("확정에 실패하면 XACK 하지 않고 재처리한다 - 저장만 되고 확정이 누락되면 안 된다")
	void keepsPendingWhenConfirmFails() {
		issue(1L);
		willThrow(new IllegalStateException("확정 실패"))
				.given(coordinator).confirmPersisted(any(), any());

		relay(3, Duration.ofSeconds(30)).runOnce();

		verify(persistenceService).persist(any());
		assertThat(pendingCount()).isEqualTo(1);
		// 저장은 끝났으므로 원복하면 안 된다
		verify(coordinator, never()).abandon(any(), any(), any(), any());
	}

	@Test
	@DisplayName("첫 확정이 실패해도 재처리에서 성공하면 XACK 한다")
	void acknowledgesAfterConfirmRetrySucceeds() {
		issue(1L);
		willThrow(new IllegalStateException("확정 실패"))
				.willDoNothing()
				.given(coordinator).confirmPersisted(any(), any());

		IssueStreamRelay relay = relay(3, Duration.ofMillis(1));
		relay.runOnce();
		assertThat(pendingCount()).isEqualTo(1);

		// 유휴 pending 을 회수해 다시 시도한다
		relay.runOnce();

		verify(coordinator, times(2)).confirmPersisted(any(), any());
		assertThat(pendingCount()).isZero();
	}

	@Test
	@DisplayName("확정 실패가 한도를 넘겨도 보상 경로를 타지 않고 pending 을 유지한다")
	void neverCompensatesAndKeepsPendingWhenConfirmExceedsLimit() {
		issue(1L);
		willThrow(new IllegalStateException("확정 실패"))
				.given(coordinator).confirmPersisted(any(), any());

		// 한도가 1이라 첫 실패에서 바로 포기 단계로 간다
		IssueStreamRelay relay = relay(1, Duration.ofMillis(1));
		relay.runOnce();

		// 저장이 커밋된 건을 보상하면 MySQL 에 행이 있는 채로 재고가 복구된다
		verify(coordinator, never()).abandon(any(), any(), any(), any());
		// XACK 하면 재확정 수단이 사라진다. pending 에 남아야 한다
		assertThat(pendingCount()).isEqualTo(1);
		verify(coordinator).recordConfirmAbandoned(any(), any(), any());
	}

	@Test
	@DisplayName("한도를 넘긴 확정도 Redis 가 회복되면 다시 확정하고 XACK 한다")
	void recoversAbandonedConfirmAfterRedisRecovers() {
		issue(1L);
		willThrow(new IllegalStateException("확정 실패"))
				.given(coordinator).confirmPersisted(any(), any());

		IssueStreamRelay relay = relay(1, Duration.ofMillis(1));
		relay.runOnce();
		assertThat(pendingCount()).isEqualTo(1);

		// Redis 회복. XCLAIM 이 회수해 다시 확정한다
		willDoNothing().given(coordinator).confirmPersisted(any(), any());
		relay.runOnce();

		assertThat(pendingCount()).isZero();
		verify(coordinator, never()).abandon(any(), any(), any(), any());
	}

	@Test
	@DisplayName("확정 실패가 여러 주기 이어져도 실패 기록은 한도 도달 시 한 번만 남긴다")
	void recordsConfirmAbandonOnlyOnceAtThreshold() {
		issue(1L);
		willThrow(new IllegalStateException("확정 실패"))
				.given(coordinator).confirmPersisted(any(), any());

		IssueStreamRelay relay = relay(1, Duration.ofMillis(1));
		relay.runOnce();
		relay.runOnce();
		relay.runOnce();

		assertThat(pendingCount()).isEqualTo(1);
		verify(coordinator, times(1)).recordConfirmAbandoned(any(), any(), any());
	}

	@Test
	@DisplayName("그룹 생성 전에 쌓인 엔트리도 읽는다")
	void readsEntriesWrittenBeforeGroupExisted() {
		issue(1L);
		issue(2L);
		issue(3L);

		relay(3, Duration.ofSeconds(30)).runOnce();

		verify(persistenceService, times(3)).persist(any());
		assertThat(pendingCount()).isZero();
	}

	@Test
	@DisplayName("보상 엔트리는 저장하지 않고 XACK 한다")
	void skipsCompensateEntries() {
		UUID requestId = issue(1L);
		processor.compensate(campaignId, 1L, requestId);

		relay(3, Duration.ofSeconds(30)).runOnce();

		// ISSUE 1건만 저장되고 COMPENSATE 엔트리는 건너뜀
		verify(persistenceService, times(1)).persist(any());
		assertThat(pendingCount()).isZero();
	}

	@Test
	@DisplayName("형식이 깨진 엔트리는 붙잡지 않고 건너뛴다")
	void skipsMalformedEntries() {
		redisTemplate.<String, String>opsForStream().add(StreamRecords.newRecord()
				.in(streamKey)
				.ofMap(Map.of("type", "ISSUE", "requestId", "not-a-uuid")));

		relay(3, Duration.ofSeconds(30)).runOnce();

		verify(persistenceService, never()).persist(any());
		assertThat(pendingCount()).isZero();
	}

	@Test
	@DisplayName("저장에 실패하면 XACK 하지 않는다")
	void keepsPendingOnFailure() {
		issue(1L);
		given(persistenceService.persist(any())).willThrow(new IllegalStateException("저장 실패"));

		relay(3, Duration.ofSeconds(30)).runOnce();

		assertThat(pendingCount()).isEqualTo(1);
		assertThat(redisTemplate.opsForStream().size(streamKey)).isEqualTo(1);
		verify(coordinator, never()).abandon(any(), any(), any(), any());
	}

	@Test
	@DisplayName("모든 Consumer Group이 ACK한 안전한 ID 이전만 XTRIM으로 제거한다")
	void trimsOnlyEntriesAcknowledgedByEveryGroup() {
		String auditGroup = "issue-audit-test";
		issue(1L);
		issue(2L);
		issue(3L);
		redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0"), auditGroup);
		IssueStreamRelay relay = relay(3, Duration.ofSeconds(30));

		// 저장 그룹만 처리한 상태라 다른 그룹이 보지 않은 엔트리는 제거할 수 없다.
		relay.runOnce();
		assertThat(redisTemplate.opsForStream().size(streamKey)).isEqualTo(3);

		List<MapRecord<String, String, String>> auditRecords =
				redisTemplate.<String, String>opsForStream().read(
						Consumer.from(auditGroup, "audit-consumer"),
						StreamReadOptions.empty().count(10),
						StreamOffset.create(streamKey, ReadOffset.lastConsumed()));
		assertThat(auditRecords).hasSize(3);
		RecordId[] auditIds = auditRecords.stream()
				.map(MapRecord::getId)
				.toArray(RecordId[]::new);
		redisTemplate.opsForStream().acknowledge(streamKey, auditGroup, auditIds);

		// 모든 그룹이 ACK한 뒤에는 마지막 ID 이전 엔트리가 제거되고 경계 엔트리 하나만 남는다.
		relay.runOnce();
		assertThat(redisTemplate.opsForStream().size(streamKey)).isEqualTo(1);
		assertThat(redisTemplate.opsForStream().pending(
				streamKey, GROUP, Range.unbounded(), 100)).isEmpty();
	}

	@Test
	@DisplayName("유휴 pending 을 회수해 다시 시도한다")
	void reclaimsIdlePending() {
		issue(1L);
		given(persistenceService.persist(any())).willThrow(new IllegalStateException("저장 실패"));
		IssueStreamRelay relay = relay(5, Duration.ofMillis(1));

		relay.runOnce();
		assertThat(pendingCount()).isEqualTo(1);

		// 유휴 기준이 1ms 라 다음 주기에 곧바로 회수
		sleepMillis(10);
		relay.runOnce();

		verify(persistenceService, times(2)).persist(any());
		assertThat(pendingCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("재시도 한도를 넘기면 되돌리고 XACK 한다")
	void abandonsAfterMaxAttempts() {
		issue(1L);
		given(persistenceService.persist(any())).willThrow(new IllegalStateException("저장 실패"));
		given(coordinator.abandon(any(), any(), any(), any()))
				.willReturn(CouponIssueCompensationResult.COMPENSATED);
		IssueStreamRelay relay = relay(1, Duration.ofMillis(1));

		relay.runOnce();

		verify(coordinator).abandon(any(), eq(IssueFailureStage.RELAY), any(), any());
		assertThat(pendingCount()).isZero();
	}

	@Test
	@DisplayName("이미 처리한 엔트리는 다시 읽지 않는다")
	void doesNotRereadAcknowledgedEntries() {
		issue(1L);
		IssueStreamRelay relay = relay(3, Duration.ofSeconds(30));

		relay.runOnce();
		relay.runOnce();

		verify(persistenceService, times(1)).persist(any());
	}

	@Test
	@DisplayName("소비 대상이 없으면 아무것도 하지 않는다")
	void doesNothingWithoutTargets() {
		given(targetProvider.campaignIds()).willReturn(List.of());

		assertThat(relay(3, Duration.ofSeconds(30)).runOnce()).isFalse();

		verify(persistenceService, never()).persist(any());
	}

	@Test
	@DisplayName("Stream 엔트리 필드가 저장 입력과 1:1 로 대응한다")
	void streamEntryMatchesContract() {
		issue(1L);

		List<MapRecord<String, String, String>> records =
				redisTemplate.<String, String>opsForStream().range(streamKey, Range.unbounded());

		assertThat(records).hasSize(1);
		assertThat(records.get(0).getValue()).containsOnlyKeys(
				IssueRecord.FIELD_TYPE,
				IssueRecord.FIELD_REQUEST_ID,
				IssueRecord.FIELD_CAMPAIGN_ID,
				IssueRecord.FIELD_USER_ID,
				IssueRecord.FIELD_BITMAP_SEGMENT_ID,
				IssueRecord.FIELD_BIT_OFFSET,
				IssueRecord.FIELD_ISSUE_SEQUENCE,
				IssueRecord.FIELD_DECIDED_AT);
	}

	@Test
	@DisplayName("원복 결과가 불확실하면 XACK 하지 않는다 - 저장도 원복도 안 된 채 메시지만 사라지면 안 된다")
	void keepsPendingWhenCompensationOutcomeUnknown() {
		issue(1L);
		given(persistenceService.persist(any())).willThrow(new IllegalStateException("저장 실패"));
		// null = 원복 여부를 알 수 없음
		given(coordinator.abandon(any(), any(), any(), any())).willReturn(null);

		relay(1, Duration.ofMillis(1)).runOnce();

		verify(coordinator).abandon(any(), eq(IssueFailureStage.RELAY), any(), any());
		assertThat(pendingCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("설정한 횟수만큼만 저장을 시도하고 포기한다 - XCLAIM 이 전달 횟수를 올린다")
	void stopsExactlyAtMaxDeliveryAttempts() {
		issue(1L);
		given(persistenceService.persist(any())).willThrow(new IllegalStateException("저장 실패"));
		given(coordinator.abandon(any(), any(), any(), any()))
				.willReturn(CouponIssueCompensationResult.COMPENSATED);
		IssueStreamRelay relay = relay(2, Duration.ofMillis(1));

		// 신규 소비 1회 + 회수 재시도. 한도(2)에 닿으면 더 시도하지 않는다
		for (int i = 0; i < 5; i++) {
			relay.runOnce();
			sleepMillis(5);
		}

		verify(persistenceService, times(2)).persist(any());
		verify(coordinator, times(1)).abandon(any(), any(), any(), any());
		assertThat(pendingCount()).isZero();
	}
}
