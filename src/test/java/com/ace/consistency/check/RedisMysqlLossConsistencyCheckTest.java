package com.ace.consistency.check;

import com.ace.consistency.common.ConsistencyCheck.CheckOutcome;
import com.ace.consistency.common.Scope;
import com.ace.coupon.redis.CouponRedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisMysqlLossConsistencyCheckTest {

	@Mock
	private NamedParameterJdbcTemplate jdbcTemplate;

	@Mock
	private StringRedisTemplate redisTemplate;

	@Mock
	private ValueOperations<String, String> valueOperations;

	private RedisMysqlLossConsistencyCheck check;

	@BeforeEach
	void setUp() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		check = new RedisMysqlLossConsistencyCheck(jdbcTemplate, redisTemplate);
	}

	@Test
	@DisplayName("Redis에 키가 없으면 기대 건수를 0으로 간주하고 판단한다")
	void passWhenRedisKeyDoesNotExist() {
		long eventId = 1L;
		String redisKey = CouponRedisKeys.campaign(eventId).sequence();

		when(valueOperations.get(redisKey)).thenReturn(null);
		when(jdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class)))
				.thenReturn(0);

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isTrue();
	}

	@Test
	@DisplayName("Redis 발급 건수와 MySQL 적재 건수가 일치하면 PASS를 반환한다")
	void passWhenCountsMatch() {
		long eventId = 1L;
		String redisKey = CouponRedisKeys.campaign(eventId).sequence();

		when(valueOperations.get(redisKey)).thenReturn("5");
		when(jdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class)))
				.thenReturn(5);

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isTrue();
	}

	@Test
	@DisplayName("Redis 발급 건수보다 MySQL 적재 건수가 적으면(유실) FAIL을 반환한다")
	void failWhenMysqlCountIsLessThanRedis() {
		long eventId = 1L;
		String redisKey = CouponRedisKeys.campaign(eventId).sequence();

		when(valueOperations.get(redisKey)).thenReturn("10");
		when(jdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class)))
				.thenReturn(8);

		CheckOutcome outcome = check.check(Scope.ofEvent(eventId));

		assertThat(outcome.isPass()).isFalse();
		assertThat(outcome.getViolationCount()).isEqualTo(1);
		
		Map<String, Object> diff = outcome.getDiffDetail();
		assertThat(diff.get("expectedCount (Redis)")).isEqualTo(10L);
		assertThat(diff.get("actualCount (MySQL)")).isEqualTo(8L);
		assertThat(diff.get("lostCount")).isEqualTo(2L);
	}
}
