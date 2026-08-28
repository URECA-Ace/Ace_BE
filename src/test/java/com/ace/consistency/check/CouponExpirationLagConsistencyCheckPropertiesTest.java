package com.ace.consistency.check;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.ace.consistency.common.Scope;

class CouponExpirationLagConsistencyCheckPropertiesTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withBean(NamedParameterJdbcTemplate.class, CouponExpirationLagConsistencyCheckPropertiesTest::mockJdbcTemplate)
			.withUserConfiguration(CouponExpirationLagConsistencyCheck.class);

	@Test
	@DisplayName("허용 지연시간이 없으면 120초 기본값으로 바인딩된다")
	void bindsDefaultAllowedDelay() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();

			CouponExpirationLagConsistencyCheck check = context.getBean(CouponExpirationLagConsistencyCheck.class);
			NamedParameterJdbcTemplate jdbcTemplate = context.getBean(NamedParameterJdbcTemplate.class);
			check.check(Scope.ofEvent(1L));

			assertAllowedDelayMicros(jdbcTemplate, 120_000_000L);
		});
	}

	@Test
	@DisplayName("허용 지연시간을 명시하면 해당 값으로 바인딩된다")
	void bindsConfiguredAllowedDelay() {
		runner.withPropertyValues("consistency.expiration.allowed-delay-ms=120000")
				.run(context -> {
					assertThat(context).hasNotFailed();

					CouponExpirationLagConsistencyCheck check = context
							.getBean(CouponExpirationLagConsistencyCheck.class);
					NamedParameterJdbcTemplate jdbcTemplate = context.getBean(NamedParameterJdbcTemplate.class);
					check.check(Scope.ofEvent(1L));

					assertAllowedDelayMicros(jdbcTemplate, 120_000_000L);
				});
	}

	private static NamedParameterJdbcTemplate mockJdbcTemplate() {
		NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
		when(jdbcTemplate.queryForList(anyString(), any(MapSqlParameterSource.class)))
				.thenReturn(List.of());
		return jdbcTemplate;
	}

	private static void assertAllowedDelayMicros(
			NamedParameterJdbcTemplate jdbcTemplate,
			long expectedMicros) {
		ArgumentCaptor<MapSqlParameterSource> captor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
		verify(jdbcTemplate).queryForList(anyString(), captor.capture());
		assertThat(captor.getValue().getValue("allowedDelayMicros")).isEqualTo(expectedMicros);
	}
}
