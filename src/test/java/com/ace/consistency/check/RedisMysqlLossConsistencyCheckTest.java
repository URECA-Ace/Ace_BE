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

class RedisMysqlLossConsistencyCheckTest extends CheckIntegrationTestBase {

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

	@Test
	@DisplayName("작업 중(Pending > 0)일 때는 유실로 보지 않고 검사를 스킵(PASS)한다")
	void passWhenPendingIsGreaterThanZero() {
		long eventId = generateUniqueId();
		
		// Mock Pending = 5
		StreamInfo.XInfoGroup groupInfo = mock(StreamInfo.XInfoGroup.class);
		when(groupInfo.pendingCount()).thenReturn(5L);
		StreamInfo.XInfoGroups groups = mock(StreamInfo.XInfoGroups.class);
		when(groups.stream()).thenReturn(List.of(groupInfo).stream());
		when(streamOperations.groups(anyString())).thenReturn(groups);

		// Mock Redis Count = 10 (But it shouldn't matter since it skips)
		when(valueOperations.get(anyString())).thenReturn("10");

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isTrue();
	}

	@Test
	@DisplayName("Pending이 0이거나 스트림이 삭제된 상태에서 데이터가 유실되었다면 확실하게 FAIL 반환 (트럭 폭파 시나리오)")
	void failWhenPendingIsZeroAndDataIsLost() {
		long eventId = generateUniqueId();
		
		// Mock Pending = 0 (트럭 비워짐 또는 폭파됨)
		StreamInfo.XInfoGroup groupInfo = mock(StreamInfo.XInfoGroup.class);
		when(groupInfo.pendingCount()).thenReturn(0L);
		StreamInfo.XInfoGroups groups = mock(StreamInfo.XInfoGroups.class);
		when(groups.stream()).thenReturn(List.of(groupInfo).stream());
		when(streamOperations.groups(anyString())).thenReturn(groups);

		// Mock Redis Count = 100 (영수증)
		when(valueOperations.get(anyString())).thenReturn("100");

		// MySQL에는 0개 저장됨 (창고 비어있음) - insertDummyIssue 호출 안 함

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
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
