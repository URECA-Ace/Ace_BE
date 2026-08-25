package com.ace.coupon.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import com.ace.coupon.dto.request.CouponEventCreateRequest;
import com.ace.coupon.dto.response.CouponEventCreateResponse;
import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.enums.IssueRequestStatus;
import com.ace.coupon.repository.CouponEventRepository;
import com.ace.coupon.service.CampaignAdminService;
import com.ace.coupon.service.CouponEventCreationPersistenceService;
import com.ace.coupon.service.CouponEventCreationService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@Tag("redis-integration")
class RedisCouponIssueIntegrationTest {

	private static final AtomicLong CAMPAIGN_ID =
			new AtomicLong(8_000_000_000_000_000L + System.currentTimeMillis());
	private static final List<Long> TEST_CAMPAIGNS = new ArrayList<>();

	private static LettuceConnectionFactory connectionFactory;
	private static StringRedisTemplate redisTemplate;
	private static CampaignRedisInitializer initializer;
	private static RedisCouponIssueProcessor processor;
	private static RedisCouponEventStatsReader statsReader;
	private static SimpleMeterRegistry meterRegistry;

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
		meterRegistry = new SimpleMeterRegistry();
		RedisLuaFailureObserver failureObserver = new RedisLuaFailureObserver(meterRegistry);
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
		statsReader = new RedisCouponEventStatsReader(
				redisTemplate,
				script("scripts/coupon-event-stats.lua", List.class));
	}

	@AfterEach
	void cleanCampaignKeys() {
		for (Long campaignId : TEST_CAMPAIGNS) {
			Set<String> keys = redisTemplate.keys("coupon:{campaign:" + campaignId + "}:*");
			if (keys != null && !keys.isEmpty()) {
				redisTemplate.delete(keys);
			}
		}
		TEST_CAMPAIGNS.clear();
	}

	@AfterAll
	static void closeRedis() {
		connectionFactory.destroy();
	}

	@Test
	@DisplayName("캠페인 초기화는 동일 설정에 멱등이고 다른 설정의 재고 덮어쓰기를 차단한다")
	void initializesCampaignIdempotently() {
		long campaignId = nextCampaignId();
		Instant now = Instant.now();

		CampaignInitializationResult first = initializer.initialize(
				campaignId, 100, now.minusSeconds(10), now.plusSeconds(600));
		CampaignInitializationResult replay = initializer.initialize(
				campaignId, 100, now.minusSeconds(10), now.plusSeconds(600));
		CampaignInitializationResult conflict = initializer.initialize(
				campaignId, 200, now.minusSeconds(10), now.plusSeconds(600));

		assertThat(first).isEqualTo(CampaignInitializationResult.INITIALIZED);
		assertThat(replay).isEqualTo(CampaignInitializationResult.ALREADY_INITIALIZED);
		assertThat(conflict).isEqualTo(CampaignInitializationResult.CONFIGURATION_CONFLICT);
		assertThat(redisTemplate.opsForValue().get(CouponRedisKeys.campaign(campaignId).stock()))
				.isEqualTo("100");
	}

	@Test
	@DisplayName("캠페인 생성 서비스가 Redis를 초기화하면 기존 발급 API 판정을 즉시 수행할 수 있다")
	void createsCampaignThenIssuesCoupon() {
		long campaignId = nextCampaignId();
		Instant now = Instant.now();
		OffsetDateTime openAt = OffsetDateTime.ofInstant(now.minusSeconds(60), ZoneId.of("Asia/Seoul"));
		OffsetDateTime closeAt = OffsetDateTime.ofInstant(now.plusSeconds(600), ZoneId.of("Asia/Seoul"));
		CouponEvent event = CouponEvent.builder()
				.id(campaignId)
				.round(24)
				.openAt(LocalDateTime.ofInstant(openAt.toInstant(), ZoneId.of("Asia/Seoul")))
				.closeAt(LocalDateTime.ofInstant(closeAt.toInstant(), ZoneId.of("Asia/Seoul")))
				.totalStock(1)
				.remainingStock(1)
				.issuedQuantity(0)
				.perUserLimit(1)
				.status(CouponEventStatus.OPEN)
				.createdAt(LocalDateTime.now())
				.updatedAt(LocalDateTime.now())
				.build();
		CouponEventCreationPersistenceService persistenceService =
				mock(CouponEventCreationPersistenceService.class);
		CouponEventRepository repository = mock(CouponEventRepository.class);
		CouponIssueRedisProperties redisProperties =
				new CouponIssueRedisProperties(Duration.ofMinutes(10), ZoneId.of("Asia/Seoul"));
		given(persistenceService.create(any(), any(), any(), any(), any(), any(), any()))
				.willReturn(event);
		CampaignAdminService campaignAdminService =
				new CampaignAdminService(
						repository,
						initializer,
						org.mockito.Mockito.mock(
								com.ace.coupon.service.CampaignRedisInitializationStateService.class),
						redisProperties);
		CouponEventCreationService creationService = new CouponEventCreationService(
				persistenceService,
				repository,
				campaignAdminService,
				redisProperties);

		CouponEventCreateResponse created = creationService.create(
				1L,
				new CouponEventCreateRequest(24, 1, openAt, closeAt));
		CouponIssueDecision issued = processor.issue(campaignId, 1L, UUID.randomUUID());

		assertThat(created.eventId()).isEqualTo(campaignId);
		assertThat(created.status()).isEqualTo(CouponEventStatus.OPEN);
		assertThat(issued.code()).isEqualTo(CouponIssueLuaCode.ACCEPTED);
		assertThat(issued.remainingStock()).isZero();
	}

	@Test
	@DisplayName("발급 판정은 멱등성·사용자 중복·재고 소진·시간 구간 반환 코드를 보장한다")
	void returnsDeterministicDecisionCodes() {
		long openCampaign = initializeOpenCampaign(1);
		UUID requestId = UUID.randomUUID();

		CouponIssueDecision accepted = processor.issue(openCampaign, 1L, requestId);
		CouponIssueDecision replay = processor.issue(openCampaign, 1L, requestId);
		CouponIssueDecision conflict = processor.issue(openCampaign, 2L, requestId);
		CouponIssueDecision duplicate = processor.issue(openCampaign, 1L, UUID.randomUUID());
		CouponIssueDecision soldOut = processor.issue(openCampaign, 2L, UUID.randomUUID());

		assertThat(accepted.code()).isEqualTo(CouponIssueLuaCode.ACCEPTED);
		assertThat(replay).isEqualTo(accepted);
		assertThat(conflict.code()).isEqualTo(CouponIssueLuaCode.IDEMPOTENCY_CONFLICT);
		assertThat(duplicate.code()).isEqualTo(CouponIssueLuaCode.ALREADY_ISSUED);
		assertThat(soldOut.code()).isEqualTo(CouponIssueLuaCode.SOLD_OUT);

		long scheduledCampaign = nextCampaignId();
		Instant now = Instant.now();
		initializer.initialize(scheduledCampaign, 1, now.plusSeconds(600), now.plusSeconds(1_200));
		assertThat(processor.issue(scheduledCampaign, 1L, UUID.randomUUID()).code())
				.isEqualTo(CouponIssueLuaCode.EVENT_NOT_OPEN);

		long closedCampaign = nextCampaignId();
		initializer.initialize(closedCampaign, 1, now.minusSeconds(600), now.minusSeconds(1));
		assertThat(processor.issue(closedCampaign, 1L, UUID.randomUUID()).code())
				.isEqualTo(CouponIssueLuaCode.EVENT_CLOSED);
	}

	@Test
	@DisplayName("발급 현황은 Redis 시각과 재고의 원자적 스냅샷으로 상태와 수량을 반환한다")
	void readsRealtimeIssuanceStats() {
		long scheduledCampaign = nextCampaignId();
		Instant now = Instant.now();
		initializer.initialize(
				scheduledCampaign, 2, now.plusSeconds(600), now.plusSeconds(1_200));
		assertThat(statsReader.read(scheduledCampaign).status())
				.isEqualTo(com.ace.coupon.enums.CouponEventStatus.SCHEDULED);

		long openCampaign = initializeOpenCampaign(2);
		UUID requestId = UUID.randomUUID();
		processor.issue(openCampaign, 1L, requestId);
		CouponEventStatsSnapshot allocated = statsReader.read(openCampaign);
		assertThat(allocated.totalStock()).isEqualTo(2L);
		assertThat(allocated.allocatedQuantity()).isOne();
		assertThat(allocated.remainingStock()).isOne();
		assertThat(allocated.status()).isEqualTo(com.ace.coupon.enums.CouponEventStatus.OPEN);
		// 확정 전에는 필드가 없음 -> 0
		assertThat(allocated.confirmedQuantity()).isZero();
		assertThat(allocated.pendingQuantity()).isOne();

		processor.confirm(openCampaign, 1L, requestId);
		CouponEventStatsSnapshot confirmed = statsReader.read(openCampaign);
		assertThat(confirmed.confirmedQuantity()).isOne();
		assertThat(confirmed.pendingQuantity()).isZero();

		// 확정된 요청은 보상 대상이 아니라서 재고와 확정 수가 그대로 유지
		assertThat(processor.compensate(openCampaign, 1L, requestId))
				.isEqualTo(CouponIssueCompensationResult.NOT_COMPENSABLE);
		CouponEventStatsSnapshot afterCompensate = statsReader.read(openCampaign);
		assertThat(afterCompensate.allocatedQuantity()).isOne();
		assertThat(afterCompensate.remainingStock()).isOne();
		assertThat(afterCompensate.confirmedQuantity()).isOne();

		long soldOutCampaign = initializeOpenCampaign(1);
		processor.issue(soldOutCampaign, 2L, UUID.randomUUID());
		assertThat(statsReader.read(soldOutCampaign).status())
				.isEqualTo(com.ace.coupon.enums.CouponEventStatus.SOLD_OUT);

		long closedCampaign = nextCampaignId();
		initializer.initialize(
				closedCampaign, 1, now.minusSeconds(600), now.minusSeconds(1));
		assertThat(statsReader.read(closedCampaign).status())
				.isEqualTo(com.ace.coupon.enums.CouponEventStatus.CLOSED);

		long missingCampaign = nextCampaignId();
		assertThat(statsReader.read(missingCampaign)).isNull();
	}

	@Test
	@DisplayName("확정은 수명주기를 한 번만 올리고 재시도와 잘못된 대상은 카운터를 건드리지 않는다")
	void confirmsIssuedRequestExactlyOnce() {
		long campaignId = initializeOpenCampaign(3);
		UUID requestId = UUID.randomUUID();
		processor.issue(campaignId, 1L, requestId);

		assertThat(processor.confirm(campaignId, 1L, requestId))
				.isEqualTo(CouponIssueConfirmResult.CONFIRMED_NOW);
		assertThat(processor.findRequest(campaignId, requestId).status())
				.isEqualTo(IssueRequestStatus.ISSUED);
		assertThat(statsReader.read(campaignId).confirmedQuantity()).isOne();

		// RELAY 재전달로 다시 들어와도 카운터는 오르지 않는다
		assertThat(processor.confirm(campaignId, 1L, requestId))
				.isEqualTo(CouponIssueConfirmResult.ALREADY_CONFIRMED);
		assertThat(statsReader.read(campaignId).confirmedQuantity()).isOne();

		// 다른 사용자로는 남의 확정을 건드릴 수 없다
		assertThat(processor.confirm(campaignId, 2L, requestId))
				.isEqualTo(CouponIssueConfirmResult.INVALID_ARGUMENT);
		assertThat(statsReader.read(campaignId).confirmedQuantity()).isOne();

		assertThat(processor.confirm(campaignId, 1L, UUID.randomUUID()))
				.isEqualTo(CouponIssueConfirmResult.REQUEST_NOT_FOUND);
	}

	@Test
	@DisplayName("보상된 요청과 거절된 요청은 확정할 수 없다")
	void rejectsConfirmOnNonPendingRequest() {
		long campaignId = initializeOpenCampaign(1);
		UUID compensated = UUID.randomUUID();
		processor.issue(campaignId, 1L, compensated);
		processor.compensate(campaignId, 1L, compensated);

		assertThat(processor.confirm(campaignId, 1L, compensated))
				.isEqualTo(CouponIssueConfirmResult.NOT_CONFIRMABLE);
		assertThat(statsReader.read(campaignId).confirmedQuantity()).isZero();

		// 재고 소진으로 거절된 요청도 확정 대상이 아니다
		processor.issue(campaignId, 2L, UUID.randomUUID());
		UUID soldOut = UUID.randomUUID();
		assertThat(processor.issue(campaignId, 3L, soldOut).code())
				.isEqualTo(CouponIssueLuaCode.SOLD_OUT);
		assertThat(processor.confirm(campaignId, 3L, soldOut))
				.isEqualTo(CouponIssueConfirmResult.NOT_CONFIRMABLE);
	}

	@Test
	@DisplayName("발급 현황은 숫자가 아닌 확정 수를 0 으로 뭉개지 않고 손상 상태로 거절한다")
	void rejectsNonNumericConfirmedQuantity() {
		long campaignId = initializeOpenCampaign(1);
		processor.issue(campaignId, 1L, UUID.randomUUID());
		redisTemplate.opsForHash()
				.put(CouponRedisKeys.campaign(campaignId).metadata(), "confirmedQuantity", "broken");

		assertThatThrownBy(() -> statsReader.read(campaignId))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Redis 상태");
	}

	@Test
	@DisplayName("발급 현황은 전체 수량보다 큰 잔여 재고를 손상 상태로 거절한다")
	void rejectsCorruptedRealtimeStats() {
		long campaignId = initializeOpenCampaign(1);
		redisTemplate.opsForValue().set(CouponRedisKeys.campaign(campaignId).stock(), "2");

		assertThatThrownBy(() -> statsReader.read(campaignId))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Redis 상태");
	}

	@Test
	@DisplayName("v2 요청 상태는 Java 조회와 Lua 멱등 재요청에서 동일하게 해석된다")
	void reusesVersionedRequestState() {
		long campaignId = initializeOpenCampaign(2);
		UUID requestId = UUID.randomUUID();
		CouponIssueDecision accepted = processor.issue(campaignId, 1L, requestId);

		CouponIssueRequestState state = processor.findRequest(campaignId, requestId);
		CouponIssueDecision replay = processor.issue(campaignId, 1L, requestId);
		CouponIssueDecision conflict = processor.issue(campaignId, 2L, requestId);

		assertThat(state.status()).isEqualTo(IssueRequestStatus.ACCEPTED);
		assertThat(replay).isEqualTo(accepted);
		assertThat(replay.issueSequence()).isEqualTo(1L);
		assertThat(replay.remainingStock()).isEqualTo(1L);
		assertThat(conflict.code()).isEqualTo(CouponIssueLuaCode.IDEMPOTENCY_CONFLICT);
	}

	@Test
	@DisplayName("저장 실패 보상은 재고와 Bitmap을 함께 원복하고 중복 보상을 차단한다")
	void compensatesStockAndBitmapAtomically() {
		long campaignId = initializeOpenCampaign(2);
		UUID requestId = UUID.randomUUID();
		CouponIssueDecision accepted = processor.issue(campaignId, 1L, requestId);

		CouponIssueCompensationResult compensated = processor.compensate(campaignId, 1L, requestId);
		CouponIssueCompensationResult replay = processor.compensate(campaignId, 1L, requestId);
		CouponIssueDecision nextIssue = processor.issue(campaignId, 1L, UUID.randomUUID());

		assertThat(accepted.code()).isEqualTo(CouponIssueLuaCode.ACCEPTED);
		assertThat(compensated).isEqualTo(CouponIssueCompensationResult.COMPENSATED);
		assertThat(replay).isEqualTo(CouponIssueCompensationResult.ALREADY_COMPENSATED);
		assertThat(nextIssue.code()).isEqualTo(CouponIssueLuaCode.ACCEPTED);
		assertThat(nextIssue.issueSequence()).isEqualTo(2L);
		assertThat(processor.findRequest(campaignId, requestId).status())
				.isEqualTo(com.ace.coupon.enums.IssueRequestStatus.COMPENSATED);
	}

	@Test
	@DisplayName("다른 사용자 위치로 보상하면 재고와 원래 사용자의 Bitmap을 변경하지 않는다")
	void rejectsCompensationForDifferentBitmapLocation() {
		long campaignId = initializeOpenCampaign(2);
		UUID requestId = UUID.randomUUID();
		processor.issue(campaignId, 1L, requestId);

		CouponIssueCompensationResult wrongUser =
				processor.compensate(campaignId, 2L, requestId);

		CouponRedisKeys.CampaignKeys keys = CouponRedisKeys.campaign(campaignId);
		assertThat(wrongUser).isEqualTo(CouponIssueCompensationResult.INVALID_ARGUMENT);
		assertThat(redisTemplate.opsForValue().get(keys.stock())).isEqualTo("1");
		assertThat(bitCount(keys.bitmap(1L).key())).isOne();
		assertThat(processor.compensate(campaignId, 1L, requestId))
				.isEqualTo(CouponIssueCompensationResult.COMPENSATED);
	}

	@Test
	@DisplayName("Redis pcall 오류는 외부 결과와 분리해 실패 단계 메트릭에 기록한다")
	void recordsPcallDiagnosticWithoutExposingRawError() {
		long campaignId = initializeOpenCampaign(1);
		CouponRedisKeys.CampaignKeys keys = CouponRedisKeys.campaign(campaignId);
		redisTemplate.delete(keys.requests());
		redisTemplate.opsForValue().set(keys.requests(), "wrong-type");

		CouponIssueDecision decision = processor.issue(campaignId, 1L, UUID.randomUUID());

		assertThat(decision.code()).isEqualTo(CouponIssueLuaCode.CORRUPTED_STATE);
		double count = meterRegistry.get("coupon.redis.lua.failures")
				.tag("script", "issue")
				.tag("stage", "ISSUE_REQUEST_READ")
				.tag("command", "HMGET")
				.tag("result", "CORRUPTED_STATE")
				.counter()
				.count();
		assertThat(count).isEqualTo(1.0);
	}

	@Test
	@Timeout(120)
	@DisplayName("재고 10,000장에 동시 요청 20,000건이 들어와도 초과 발급과 음수 재고가 없다")
	void preventsOverIssueUnderTwentyThousandConcurrentRequests() throws Exception {
		long campaignId = initializeOpenCampaign(10_000);
		int requestCount = 20_000;
		CountDownLatch start = new CountDownLatch(1);
		List<Future<CouponIssueDecision>> futures = new ArrayList<>(requestCount);

		try (var executor = Executors.newFixedThreadPool(256)) {
			for (int index = 1; index <= requestCount; index++) {
				long userId = index;
				UUID requestId = UUID.randomUUID();
				futures.add(executor.submit(() -> {
					start.await();
					return processor.issue(campaignId, userId, requestId);
				}));
			}
			start.countDown();

			long acceptedCount = 0;
			long soldOutCount = 0;
			for (Future<CouponIssueDecision> future : futures) {
				CouponIssueLuaCode code = future.get().code();
				if (code == CouponIssueLuaCode.ACCEPTED) {
					acceptedCount++;
				} else if (code == CouponIssueLuaCode.SOLD_OUT) {
					soldOutCount++;
				}
			}

			CouponRedisKeys.CampaignKeys keys = CouponRedisKeys.campaign(campaignId);
			assertThat(acceptedCount).isEqualTo(10_000);
			assertThat(soldOutCount).isEqualTo(10_000);
			assertThat(redisTemplate.opsForValue().get(keys.stock())).isEqualTo("0");
			assertThat(redisTemplate.opsForValue().get(keys.sequence())).isEqualTo("10000");
			assertThat(bitCount(keys.bitmap(1L).key())).isEqualTo(10_000L);
			assertThat(redisTemplate.opsForStream().size(keys.issueStream())).isEqualTo(10_000L);
		}
	}

	@Test
	@Timeout(60)
	@DisplayName("동일 사용자의 동시 요청은 정확히 한 건만 승인한다")
	void acceptsOnlyOneConcurrentRequestPerUser() throws Exception {
		long campaignId = initializeOpenCampaign(1_000);
		int requestCount = 1_000;
		CountDownLatch start = new CountDownLatch(1);
		List<Future<CouponIssueDecision>> futures = new ArrayList<>(requestCount);

		try (var executor = Executors.newFixedThreadPool(128)) {
			for (int index = 0; index < requestCount; index++) {
				futures.add(executor.submit(() -> {
					start.await();
					return processor.issue(campaignId, 77L, UUID.randomUUID());
				}));
			}
			start.countDown();

			long acceptedCount = 0;
			long duplicateCount = 0;
			for (Future<CouponIssueDecision> future : futures) {
				CouponIssueLuaCode code = future.get().code();
				acceptedCount += code == CouponIssueLuaCode.ACCEPTED ? 1 : 0;
				duplicateCount += code == CouponIssueLuaCode.ALREADY_ISSUED ? 1 : 0;
			}

			assertThat(acceptedCount).isOne();
			assertThat(duplicateCount).isEqualTo(999);
			assertThat(redisTemplate.opsForValue().get(CouponRedisKeys.campaign(campaignId).stock()))
					.isEqualTo("999");
		}
	}

	private long initializeOpenCampaign(int stock) {
		long campaignId = nextCampaignId();
		Instant now = Instant.now();
		assertThat(initializer.initialize(campaignId, stock, now.minusSeconds(60), now.plusSeconds(600)))
				.isEqualTo(CampaignInitializationResult.INITIALIZED);
		return campaignId;
	}

	private long nextCampaignId() {
		long campaignId = CAMPAIGN_ID.incrementAndGet();
		TEST_CAMPAIGNS.add(campaignId);
		return campaignId;
	}

	private Long bitCount(String key) {
		byte[] rawKey = redisTemplate.getStringSerializer().serialize(key);
		return redisTemplate.execute((RedisCallback<Long>) connection ->
				connection.stringCommands().bitCount(rawKey));
	}

	private static <T> RedisScript<T> script(String location, Class<T> resultType) {
		DefaultRedisScript<T> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource(location));
		script.setResultType(resultType);
		return script;
	}
}
