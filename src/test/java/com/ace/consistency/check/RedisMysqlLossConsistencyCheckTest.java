package com.ace.consistency.check;

import com.ace.consistency.common.ConsistencyCheck.CheckOutcome;
import com.ace.consistency.common.Scope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisMysqlLossConsistencyCheckTest extends CheckIntegrationTestBase {

	@MockitoBean
	private StringRedisTemplate redisTemplate;

	private RedisMysqlLossConsistencyCheck check;
	private ValueOperations<String, String> valueOperations;

	@BeforeEach
	void setUp() {
		check = new RedisMysqlLossConsistencyCheck(jdbcTemplate, redisTemplate);
		valueOperations = mock(ValueOperations.class);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
	}

	@Test
	@DisplayName("Redis 발급 건수와 MySQL 적재 건수가 일치하면 PASS 반환")
	void passWhenCountsMatch() {
		long eventId = generateUniqueId();
		// Mock Redis Count = 2
		when(valueOperations.get(anyString())).thenReturn("2");

		// Insert 2 records into MySQL (Testcontainers)
		insertDummyIssue(eventId, 1L);
		insertDummyIssue(eventId, 2L);

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isTrue();
	}

	@Test
	@DisplayName("Redis 발급 건수보다 MySQL 적재 건수가 적으면 유실로 간주하여 FAIL 반환")
	void failWhenMysqlHasLessRecords() {
		long eventId = generateUniqueId();
		// Mock Redis Count = 3
		when(valueOperations.get(anyString())).thenReturn("3");

		// Insert only 2 records into MySQL (Testcontainers)
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
		// Mock Redis Count = null (expired or missing)
		when(valueOperations.get(anyString())).thenReturn(null);

		// Insert some records into MySQL (Testcontainers)
		insertDummyIssue(eventId, 1L);

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isFalse();
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
