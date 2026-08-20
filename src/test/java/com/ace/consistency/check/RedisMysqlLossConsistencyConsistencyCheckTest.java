package com.ace.consistency.check;

import com.ace.consistency.common.ConsistencyCheck.CheckOutcome;
import com.ace.consistency.common.Scope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisMysqlLossConsistencyConsistencyCheckTest extends ConsistencyCheckIntegrationTestBase {

	@MockitoBean
	private StringRedisTemplate redisTemplate;

	private RedisMysqlLossConsistencyConsistencyCheck check;
	private ValueOperations<String, String> valueOperations;
	private StreamOperations<String, Object, Object> streamOperations;

	@BeforeEach
	void setUp() {
		check = new RedisMysqlLossConsistencyConsistencyCheck(jdbcTemplate, redisTemplate);
		valueOperations = mock(ValueOperations.class);
		streamOperations = mock(StreamOperations.class);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(redisTemplate.opsForStream()).thenReturn(streamOperations);
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
	}

	@Test
	@DisplayName("Redis 키가 만료되거나 없는 경우(0건), MySQL 건수와 다르면 FAIL 반환")
	void failWhenRedisKeyIsNullAndMysqlHasRecords() {
		long eventId = generateUniqueId();
		insertDummyEvent(eventId, 10L);
		
		// Mock Redis remainingStock = null (expected: 10 - null(10) = 0)
		when(valueOperations.get(anyString())).thenReturn(null);

		// Insert some records into MySQL
		insertDummyIssue(eventId, 1L);

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isFalse();
	}

	@Test
	@DisplayName("작업 중(Pending > 0)일 때는 유실로 보지 않고 검사를 스킵(PASS)한다")
	void passWhenPendingIsGreaterThanZero() {
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

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isTrue();
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
