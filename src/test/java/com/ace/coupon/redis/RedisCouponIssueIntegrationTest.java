package com.ace.coupon.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
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

import com.ace.coupon.enums.IssueRequestStatus;

@Tag("redis-integration")
class RedisCouponIssueIntegrationTest {

	private static final AtomicLong CAMPAIGN_ID =
			new AtomicLong(8_000_000_000_000_000L + System.currentTimeMillis());
	private static final List<Long> TEST_CAMPAIGNS = new ArrayList<>();

	private static LettuceConnectionFactory connectionFactory;
	private static StringRedisTemplate redisTemplate;
	private static CampaignRedisInitializer initializer;
	private static RedisCouponIssueProcessor processor;

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
		initializer = new CampaignRedisInitializer(
				redisTemplate,
				script("scripts/coupon-campaign-initialize.lua", Long.class),
				properties);
		processor = new RedisCouponIssueProcessor(
				redisTemplate,
				script("scripts/coupon-issue.lua", List.class),
				script("scripts/coupon-issue-compensate.lua", Long.class));
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
	@DisplayName("최적화 전 7필드 요청 상태도 멱등 결과로 재사용한다")
	void reusesLegacyRequestState() {
		long campaignId = initializeOpenCampaign(2);
		UUID requestId = UUID.randomUUID();
		long decidedAt = Instant.now().toEpochMilli();
		CouponRedisKeys.CampaignKeys keys = CouponRedisKeys.campaign(campaignId);
		redisTemplate.opsForHash().put(
				keys.requests(),
				requestId.toString(),
				"1|0|ACCEPTED|1|1|" + decidedAt + "|1730000000000-0");

		CouponIssueRequestState state = processor.findRequest(campaignId, requestId);
		CouponIssueDecision replay = processor.issue(campaignId, 1L, requestId);
		CouponIssueDecision conflict = processor.issue(campaignId, 2L, requestId);

		assertThat(state.status()).isEqualTo(IssueRequestStatus.ACCEPTED);
		assertThat(replay.code()).isEqualTo(CouponIssueLuaCode.ACCEPTED);
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
		assertThat(replay).isEqualTo(CouponIssueCompensationResult.NOT_COMPENSABLE);
		assertThat(nextIssue.code()).isEqualTo(CouponIssueLuaCode.ACCEPTED);
		assertThat(nextIssue.issueSequence()).isEqualTo(2L);
		assertThat(processor.findRequest(campaignId, requestId).status())
				.isEqualTo(com.ace.coupon.enums.IssueRequestStatus.COMPENSATED);
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
