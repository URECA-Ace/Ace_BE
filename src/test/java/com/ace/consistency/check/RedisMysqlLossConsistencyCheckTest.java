package com.ace.consistency.check;

import com.ace.common.ErrorCode;
import com.ace.common.exception.ConsistencyCheckException;
import com.ace.consistency.common.ConsistencyCheck.CheckOutcome;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.ViolationTargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisMysqlLossConsistencyCheckTest extends ConsistencyCheckIntegrationTestBase {

	@MockitoBean
	private StringRedisTemplate redisTemplate;

	private RedisMysqlLossConsistencyCheck check;
	private ValueOperations<String, String> valueOperations;
	private StreamOperations<String, Object, Object> streamOperations;

	@BeforeEach
	void setUp() {
		check = new RedisMysqlLossConsistencyCheck(jdbcTemplate, redisTemplate);
		valueOperations = mock(ValueOperations.class);
		streamOperations = mock(StreamOperations.class);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(redisTemplate.opsForStream()).thenReturn(streamOperations);

		// @SpringBootTest는 전체 컨텍스트를 띄우므로 ConsistencySchedulerCoordinator에 자기 자신을
		// 등록하는 각 스케줄러(@PostConstruct)가 이 mock redisTemplate으로 ConsistencyScheduleStore를
		// 거쳐 opsForHash()를 호출한다. 스텁이 없으면 null을 반환해 NPE로 컨텍스트 로딩 자체가 실패한다.
		HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
		when(redisTemplate.opsForHash()).thenReturn(hashOperations);

		// 기본적으로 스트림 키가 없을 때 던지는 예외를 모킹하여 Pending=0 상태를 시뮬레이션
		org.springframework.data.redis.connection.stream.StreamInfo.XInfoGroups emptyGroups = mock(org.springframework.data.redis.connection.stream.StreamInfo.XInfoGroups.class);
		when(emptyGroups.stream()).thenReturn(java.util.stream.Stream.empty());
		when(streamOperations.groups(anyString())).thenReturn(emptyGroups);
	}

	@Test
	@DisplayName("Redis 발급 건수와 MySQL 적재 건수가 일치하면 PASS 반환")
	void passWhenCountsMatch() {
		long eventId = generateUniqueId();
		insertDummyEvent(eventId, 10L);
		
		// Mock Redis remainingStock = 8 (expected: 10 - 8 = 2)
		when(valueOperations.get(anyString())).thenReturn("8");

		// Insert 2 records into MySQL (Testcontainers)
		insertDummyIssue(eventId, 1L);
		insertDummyIssue(eventId, 2L);

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isTrue();
	}

	@Test
	@DisplayName("Redis 기대 발급 건수보다 MySQL 적재 건수가 적으면 유실로 간주하여 FAIL 반환")
	void failWhenMysqlHasLessRecords() {
		long eventId = generateUniqueId();
		insertDummyEvent(eventId, 10L);
		
		// Mock Redis remainingStock = 7 (expected: 10 - 7 = 3)
		when(valueOperations.get(anyString())).thenReturn("7");

		// Insert only 2 records into MySQL
		insertDummyIssue(eventId, 1L);
		insertDummyIssue(eventId, 2L);

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
		assertThat(outcome.getViolations()).singleElement().satisfies(violation -> {
			assertThat(violation.getTargetType()).isEqualTo(ViolationTargetType.EVENT);
			assertThat(violation.getTargetId()).isEqualTo(eventId);
		});
	}

	@Test
	@DisplayName("Redis 키가 만료된 경우(Redis=null, MySQL>0) 영구 검증 불가(CHECK_IMPOSSIBLE) 예외 발생")
	void throwExceptionWhenRedisKeyExpired() {
		long eventId = generateUniqueId();
		insertDummyEvent(eventId, 10L);
		
		// Mock Redis remainingStock = null (expected: 10 - null(10) = 0)
		when(valueOperations.get(anyString())).thenReturn(null);

		// Insert some records into MySQL
		insertDummyIssue(eventId, 1L);

		assertThatThrownBy(() -> check.check(Scope.ofEvent(eventId)))
				.isInstanceOf(ConsistencyCheckException.class)
				.hasMessageContaining("만료")
				.satisfies(ex -> assertThat(((ConsistencyCheckException) ex).getErrorCode())
						.isEqualTo(ErrorCode.CHECK_IMPOSSIBLE));
	}

	@Test
	@DisplayName("작업 중(Pending > 0)일 때는 가짜 알람 방지를 위해 검증 보류(CHECK_POSTPONED) 예외 발생")
	void throwExceptionWhenPendingIsGreaterThanZero() {
		long eventId = generateUniqueId();
		insertDummyEvent(eventId, 10L);
		
		// Mock Pending = 5
		StreamInfo.XInfoGroup groupInfo = mock(StreamInfo.XInfoGroup.class);
		when(groupInfo.pendingCount()).thenReturn(5L);
		StreamInfo.XInfoGroups groups = mock(StreamInfo.XInfoGroups.class);
		when(groups.stream()).thenReturn(List.of(groupInfo).stream());
		when(streamOperations.groups(anyString())).thenReturn(groups);

		// Mock Redis remainingStock = 0 (But it shouldn't matter since it skips)
		when(valueOperations.get(anyString())).thenReturn("0");

		assertThatThrownBy(() -> check.check(Scope.ofEvent(eventId)))
				.isInstanceOf(ConsistencyCheckException.class)
				.hasMessageContaining("PENDING")
				.satisfies(ex -> assertThat(((ConsistencyCheckException) ex).getErrorCode())
						.isEqualTo(ErrorCode.CHECK_POSTPONED));
	}

	@Test
	@DisplayName("RELAY 컨슈머 미기동 시나리오: Redis만 승인되고 MySQL 미적재 상태를 위반으로 잡아내지만, issue_failure_log 에는 아무 기록도 없다")
	void detectsViolationWhileDlqStaysEmptyWhenRelayConsumerNeverPersists() {
		long eventId = generateUniqueId();
		// IssueStreamRelay 가 죽어있는 상태를 흉내낸다: coupon-issue.lua 가 Redis 재고만 3건 차감하고
		// MySQL coupon_issue 에는 아무 것도 안 쌓인 채로 방치된다. 이 경로는 예외를 던지지 않으므로
		// IssuePersistenceCoordinator 의 실패 기록 로직(JpaIssueFailureRecorder)이 아예 호출되지 않는다.
		insertDummyEvent(eventId, 10L);
		when(valueOperations.get(anyString())).thenReturn("7"); // expected = 10 - 7 = 3, actual = 0

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);

		long dlqCount = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM issue_failure_log WHERE event_id = :eventId",
				new MapSqlParameterSource("eventId", eventId),
				Long.class);
		assertThat(dlqCount)
				.as("DLQ 는 예외가 발생한 적이 없으므로 이 드리프트를 전혀 모른다")
				.isZero();
	}

	@Test
	@DisplayName("장애 복구 시나리오: Redis가 재초기화되어도 MySQL total_stock을 기준으로 정확하게 유실을 판단(PASS)한다")
	void passDisasterRecoveryScenario() {
		long eventId = generateUniqueId();
		
		// 1. 최초 총 재고는 1000개
		insertDummyEvent(eventId, 1000L);
		
		// 2. 장애 전 100개 발급 (DB에 100개 저장됨)
		for (long i = 1; i <= 100; i++) {
			insertDummyIssue(eventId, i);
		}
		
		// 3. 💥 장애 발생! Redis 데이터 전부 날아가고, DB를 기준으로 잔여재고 900개로 재초기화됨
		// 4. 복구 후 200개가 추가로 발급됨 (DB에 총 300개 저장됨)
		for (long i = 101; i <= 300; i++) {
			insertDummyIssue(eventId, i);
		}
		
		// 5. 현재 Redis의 남은 재고는 700개 (900개 세팅 후 200개 소진)
		when(valueOperations.get(anyString())).thenReturn("700");
		
		// 6. 검증 수행! (예상 기대건수: 1000 - 700 = 300건 == 실제 DB 건수 300건)
		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isTrue();
	}

	private void insertDummyEvent(long eventId, long totalStock) {
		String sql = """
                INSERT INTO coupon_event (event_id, coupon_id, round, open_at, close_at, total_stock, remaining_stock, issued_quantity, per_user_limit, status, created_at, updated_at)
                VALUES (:eventId, :eventId, 1, NOW(), DATE_ADD(NOW(), INTERVAL 7 DAY), :totalStock, :totalStock, 0, 1, 'OPEN', NOW(), NOW())
                """;
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("eventId", eventId)
				.addValue("totalStock", totalStock);
		jdbcTemplate.update(sql, params);
	}

	private void insertDummyIssue(long eventId, Long issueSequence) {
		String sql = """
                INSERT INTO coupon_issue (event_id, user_id, issue_sequence, request_id, status, issued_at, valid_from, valid_to, created_at)
                VALUES (:eventId, :userId, :issueSequence, :requestId, 'ISSUED', NOW(), NOW(), DATE_ADD(NOW(), INTERVAL 7 DAY), NOW())
                """;
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("eventId", eventId)
				.addValue("userId", generateUniqueId())
				.addValue("issueSequence", issueSequence)
				.addValue("requestId", "req-" + generateUniqueId());
		
		jdbcTemplate.update(sql, params);
	}
}
