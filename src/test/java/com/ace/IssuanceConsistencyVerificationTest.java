package com.ace;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MySQLContainer;

import com.ace.consistency.check.CouponExpirationLagConsistencyCheck;
import com.ace.consistency.check.CouponHistoryStructuralConsistencyCheck;
import com.ace.consistency.check.CouponIssueHistoryStateConsistencyCheck;
import com.ace.consistency.check.CouponIssueStructuralConsistencyCheck;
import com.ace.consistency.check.IssueHistoryTimeSyncConsistencyCheck;
import com.ace.consistency.check.StockConsistencyCheck;
import com.ace.consistency.common.ConsistencyVerificationRunner;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import com.ace.consistency.common.VerificationResult;
import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.issuance.IssuanceTestFixture;
import com.ace.coupon.redis.CampaignRedisInitializer;
import com.ace.coupon.repository.CouponEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 실제 발급 API 부하(2만 요청/1만 재고, SYNC 경로) 이후 사후처리 성격으로 정합성 검증을 돌리는 테스트.
 *
 * 부하 생성과 전체 정합성 검증은 couponIssueService/runner를 직접 호출하는 대신, 실제 운영에서 쓰이는
 * HTTP API({@code POST /api/v1/events/{eventId}/issues}, {@code POST /internal/consistency/verify})를
 * 그대로 호출한다. 전체 정합성 검증은 ALL 스코프 배치라 DB에 있는 모든 이벤트를 훑으므로, 이 클래스
 * 전용 Testcontainers MySQL을 띄워 이 테스트가 만든 이벤트 하나만 존재하는 상태에서 검증한다.
 *
 * 부하는 클래스당 한 번만 발생시키고({@link #loadCouponIssuanceOnce()}), 이후 각 테스트가 그 위에
 * 서로 다른 위반 데이터 한 건씩을 심어 해당 Check가 실제로 잡아내는지 확인한다. 심은 데이터는
 * {@link #restoreBaseline()}에서 매번 제거해 테스트 간 상태가 섞이지 않게 한다.
 * (단, 개별 위반 탐지 테스트에 대응하는 이벤트 단위 수동 검증 API는 없어서, 이 테스트들은 지금처럼
 * ConsistencyVerificationRunner.run()을 직접 호출한다)
 *
 * 1인 1매(uk_coupon_issue_event_user)와 발급 순번 중복(uk_coupon_issue_event_sequence)은
 * DB 유니크 제약이 물리적으로 막고 있어 일반 INSERT로 재현할 수 없다. 여기서는 정상 케이스 통과 여부만 함께 확인한다.
 */
@Tag("issuance-accuracy")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = {
		"coupon.issue.persistence.mode=SYNC",
		"spring.jpa.open-in-view=false",
		"spring.jpa.show-sql=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IssuanceConsistencyVerificationTest {

	private static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
			.withDatabaseName("ace")
			.withUsername("root")
			.withPassword("1234");

	static {
		mysql.start();
	}

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", mysql::getJdbcUrl);
		registry.add("spring.datasource.username", mysql::getUsername);
		registry.add("spring.datasource.password", mysql::getPassword);
		registry.add("spring.datasource.driver-class-name", mysql::getDriverClassName);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
	}

	@Autowired
	private TestRestTemplate restTemplate;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Autowired
	private CampaignRedisInitializer campaignRedisInitializer;

	@Autowired
	private CouponEventRepository couponEventRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private ConsistencyVerificationRunner runner;

	@Autowired
	private JobRepository jobRepository;

	@Autowired
	private StockConsistencyCheck stockConsistencyCheck;

	@Autowired
	private CouponIssueStructuralConsistencyCheck issueStructuralCheck;

	@Autowired
	private CouponHistoryStructuralConsistencyCheck historyStructuralCheck;

	@Autowired
	private CouponIssueHistoryStateConsistencyCheck issueHistoryStateCheck;

	@Autowired
	private IssueHistoryTimeSyncConsistencyCheck timeSyncCheck;

	@Autowired
	private CouponExpirationLagConsistencyCheck expirationLagCheck;

	private long eventId;
	private int stock;
	private long baselineMaxIssueId;

	@BeforeAll
	@Timeout(600)
	void loadCouponIssuanceOnce() throws Exception {
		stock = IssuanceTestFixture.STOCK;
		int requests = IssuanceTestFixture.REQUESTS;

		IssuanceTestFixture.ensureUsers(jdbcTemplate, requests);
		eventId = IssuanceTestFixture.createEvent(jdbcTemplate, stock);
		CouponEvent event = couponEventRepository.findById(eventId).orElseThrow();
		campaignRedisInitializer.initialize(event);

		List<Callable<String>> tasks = new ArrayList<>(requests);
		for (int i = 1; i <= requests; i++) {
			long userId = i;
			tasks.add(() -> attempt(userId, UUID.randomUUID()));
		}
		Map<String, Integer> outcomes = IssuanceTestFixture.tally(
				IssuanceTestFixture.runConcurrently(IssuanceTestFixture.THREADS, tasks));

		assertThat(outcomes.getOrDefault("ACCEPTED", 0)).isEqualTo(stock);
		assertThat(outcomes.getOrDefault("SOLD_OUT", 0)).isEqualTo(requests - stock);

		baselineMaxIssueId = jdbcTemplate.queryForObject(
				"SELECT COALESCE(MAX(issue_id), 0) FROM coupon_issue WHERE event_id = ?", Long.class, eventId);
	}

	@AfterAll
	void tearDownEvent() {
		redisTemplate.delete(redisTemplate.keys("coupon:{campaign:" + eventId + "}:*"));
		IssuanceTestFixture.deleteEvent(jdbcTemplate, eventId);
	}

	// 각 테스트가 심은 위반 데이터가 다음 테스트로 새지 않도록 부하 직후 상태로 되돌린다
	@AfterEach
	void restoreBaseline() {
		jdbcTemplate.update("""
				DELETE h FROM coupon_history h JOIN coupon_issue i ON i.issue_id = h.issue_id
				WHERE i.event_id = ? AND i.issue_id > ?
				""", eventId, baselineMaxIssueId);
		jdbcTemplate.update(
				"DELETE FROM coupon_issue WHERE event_id = ? AND issue_id > ?", eventId, baselineMaxIssueId);
	}

	// 발급 성공이면 "ACCEPTED", 거절이면 에러 코드 이름. 실제 발급 API(POST /api/v1/events/{eventId}/issues)를 그대로 호출한다
	private String attempt(long userId, UUID idempotencyKey) {
		HttpHeaders headers = new HttpHeaders();
		headers.set("Idempotency-Key", idempotencyKey.toString());
		headers.setContentType(MediaType.APPLICATION_JSON);
		HttpEntity<String> request = new HttpEntity<>("{\"userId\":" + userId + "}", headers);

		ResponseEntity<String> response = restTemplate.postForEntity(
				"/api/v1/events/{eventId}/issues", request, String.class, eventId);

		if (response.getStatusCode() == HttpStatus.ACCEPTED) {
			return "ACCEPTED";
		}
		return errorCodeOf(response.getBody());
	}

	private String errorCodeOf(String body) {
		try {
			return objectMapper.readTree(body).path("error").path("code").asText();
		} catch (Exception e) {
			throw new RuntimeException("에러 응답 파싱 실패: " + body, e);
		}
	}

	// 부하에 참여하지 않아 이 이벤트에 발급 이력이 없는 user_id를 하나 빌려온다 (FK/유니크 제약을 안전하게 피한다)
	private long freshUserId() {
		return jdbcTemplate.queryForObject("""
				SELECT user_id FROM user
				WHERE user_id NOT IN (SELECT user_id FROM coupon_issue WHERE event_id = ?)
				LIMIT 1
				""", Long.class, eventId);
	}

	private long insertIssue(long userId, long sequence, String status, String requestId,
			LocalDateTime issuedAt, LocalDateTime validFrom, LocalDateTime validTo) {
		jdbcTemplate.update("""
				INSERT INTO coupon_issue
				    (event_id, user_id, issue_sequence, request_id, status, issued_at, valid_from, valid_to, created_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", eventId, userId, sequence, requestId, status, issuedAt, validFrom, validTo, issuedAt);
		return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private void insertHistory(long issueId, String fromStatus, String toStatus,
			LocalDateTime occurredAt, LocalDateTime recordedAt) {
		jdbcTemplate.update("""
				INSERT INTO coupon_history (issue_id, from_status, to_status, occurred_at, recorded_at)
				VALUES (?, ?, ?, ?, ?)
				""", issueId, fromStatus, toStatus, occurredAt, recordedAt);
	}

	@Test
	@Order(1)
	@DisplayName("1만 건이 정상 적재되면 모든 정합성 검증을 통과한다")
	@Timeout(180)
	void passesAllChecksOnCleanLoad() throws Exception {
		Long maxIdBefore = jdbcTemplate.queryForObject(
				"SELECT COALESCE(MAX(id), 0) FROM verification_result", Long.class);

		ResponseEntity<String> response = restTemplate.postForEntity("/internal/consistency/verify", null, String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		long jobExecutionId = objectMapper.readTree(response.getBody()).path("data").asLong();

		JobExecution finished = awaitCompletion(jobExecutionId);
		assertThat(finished.getStatus()).isEqualTo(BatchStatus.COMPLETED);

		List<Map<String, Object>> results = jdbcTemplate.queryForList(
				"SELECT check_name, status, violation_count, diff_detail FROM verification_result WHERE id > ?",
				maxIdBefore);
		assertThat(results).isNotEmpty();
		assertThat(results).allMatch(r -> "PASS".equals(r.get("status")), "결과: " + results);
	}

	// runAsync()는 배치 Job을 별도 스레드에서 시작만 시키고 바로 반환하므로, 더 이상 실행 중이 아닐 때까지 폴링한다
	private JobExecution awaitCompletion(long jobExecutionId) {
		long deadline = System.currentTimeMillis() + 120_000;
		JobExecution latest = jobRepository.getJobExecution(jobExecutionId);
		while (latest.getStatus().isRunning()) {
			if (System.currentTimeMillis() > deadline) {
				Assertions.fail("배치 Job이 제한 시간(120s) 내에 끝나지 않았습니다. status=" + latest.getStatus());
			}
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				Assertions.fail("대기 중 인터럽트가 발생했습니다.");
			}
			latest = jobRepository.getJobExecution(jobExecutionId);
		}
		return latest;
	}

	@Test
	@Order(2)
	@DisplayName("재고 카운터에 반영되지 않은 초과 발급이 섞이면 재고 정합성 검증이 잡아낸다")
	void detectsOverIssuance() {
		LocalDateTime now = LocalDateTime.now();
		insertIssue(freshUserId(), stock + 1L, "ISSUED", UUID.randomUUID().toString(), now, now, now.plusDays(7));

		List<VerificationResult> results = runner.run(
				List.of(stockConsistencyCheck), Scope.ofEvent(eventId), TriggerType.ON_DEMAND);

		assertThat(results.get(0).isPass()).isFalse();
		assertThat(results.get(0).getViolationCount()).isEqualTo(1);
	}

	@Test
	@Order(3)
	@DisplayName("필수값이 깨진 발급 건이 섞이면 발급 구조 정합성 검증이 잡아낸다")
	void detectsStructuralViolation() {
		LocalDateTime now = LocalDateTime.now();
		insertIssue(freshUserId(), stock + 2L, "ISSUED", "not-a-uuid", now, now, now.plusDays(7));

		List<VerificationResult> results = runner.run(
				List.of(issueStructuralCheck), Scope.ofEvent(eventId), TriggerType.ON_DEMAND);

		assertThat(results.get(0).isPass()).isFalse();
		assertThat(results.get(0).getViolationCount()).isGreaterThanOrEqualTo(1);
	}

	@Test
	@Order(4)
	@DisplayName("허용되지 않은 상태 전이 이력이 섞이면 이력 구조 정합성 검증이 잡아낸다")
	void detectsInvalidHistoryTransition() {
		LocalDateTime now = LocalDateTime.now();
		long issueId = insertIssue(
				freshUserId(), stock + 3L, "ISSUED", UUID.randomUUID().toString(), now, now, now.plusDays(7));
		insertHistory(issueId, null, "ISSUED", now, now);
		// EXPIRED -> USED 는 허용되지 않는 상태 전이다
		insertHistory(issueId, "EXPIRED", "USED", now.plusMinutes(1), now.plusMinutes(1));

		List<VerificationResult> results = runner.run(
				List.of(historyStructuralCheck), Scope.ofEvent(eventId), TriggerType.ON_DEMAND);

		assertThat(results.get(0).isPass()).isFalse();
		assertThat(results.get(0).getViolationCount()).isGreaterThanOrEqualTo(1);
	}

	@Test
	@Order(5)
	@DisplayName("이력이 하나도 없는 발급 건이 섞이면 상태 동기화 정합성 검증이 잡아낸다")
	void detectsMissingHistory() {
		LocalDateTime now = LocalDateTime.now();
		insertIssue(freshUserId(), stock + 4L, "ISSUED", UUID.randomUUID().toString(), now, now, now.plusDays(7));
		// 의도적으로 coupon_history 를 남기지 않는다

		List<VerificationResult> results = runner.run(
				List.of(issueHistoryStateCheck), Scope.ofEvent(eventId), TriggerType.ON_DEMAND);

		assertThat(results.get(0).isPass()).isFalse();
		assertThat(results.get(0).getViolationCount()).isEqualTo(1);
	}

	@Test
	@Order(6)
	@DisplayName("발급 시각과 이력 시각이 크게 어긋나면 시간 동기화 정합성 검증이 잡아낸다")
	void detectsTimeDesync() {
		LocalDateTime issuedAt = LocalDateTime.now();
		long issueId = insertIssue(freshUserId(), stock + 5L, "ISSUED", UUID.randomUUID().toString(),
				issuedAt, issuedAt, issuedAt.plusDays(7));
		// 최초 발급 이력의 occurred_at 을 issued_at 과 5초 어긋나게 기록한다 (허용 오차 1초)
		insertHistory(issueId, null, "ISSUED", issuedAt.plusSeconds(5), issuedAt.plusSeconds(5));

		List<VerificationResult> results = runner.run(
				List.of(timeSyncCheck), Scope.ofEvent(eventId), TriggerType.ON_DEMAND);

		assertThat(results.get(0).isPass()).isFalse();
		assertThat(results.get(0).getViolationCount()).isEqualTo(1);
	}

	@Test
	@Order(7)
	@DisplayName("만료 배치 처리 지연을 넘긴 발급 건이 섞이면 만료 지연 정합성 검증이 잡아낸다")
	void detectsExpirationBatchDelay() {
		// 기본 허용 지연은 24시간(application.properties) - valid_to 를 25시간 전으로 만들어 넘긴다
		LocalDateTime validTo = LocalDateTime.now().minusHours(25);
		LocalDateTime issuedAt = validTo.minusDays(7);
		insertIssue(freshUserId(), stock + 6L, "ISSUED", UUID.randomUUID().toString(), issuedAt, issuedAt, validTo);

		List<VerificationResult> results = runner.run(
				List.of(expirationLagCheck), Scope.ofEvent(eventId), TriggerType.ON_DEMAND);

		assertThat(results.get(0).isPass()).isFalse();
		assertThat(results.get(0).getViolationCount()).isEqualTo(1);
	}
}
