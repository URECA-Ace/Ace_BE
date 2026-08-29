package com.ace.consistency.inject.injector;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.ace.common.ErrorCode;
import com.ace.common.exception.ConsistencyCheckException;
import com.ace.consistency.inject.ConsistencyViolationInjector;
import com.ace.consistency.inject.InjectionResult;

import lombok.RequiredArgsConstructor;

/**
 * StockConsistencyCheck용 위반 주입기.
 * coupon_event.remaining_stock을 1 감소시켜, coupon_issue의 실제 활성 발급 건수와
 * coupon_event에 캐시된 카운터가 어긋나는 "카운터 표류" 상황을 재현한다.
 */
@Component
@RequiredArgsConstructor
public class StockConsistencyViolationInjector implements ConsistencyViolationInjector {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	private static final String CHECK_NAME = "StockConsistencyCheck";

	private static final String CORRUPT_SQL = """
			UPDATE coupon_event SET remaining_stock = remaining_stock - 1
			WHERE event_id = :eventId
			""";

	@Override
	public String checkName() {
		return CHECK_NAME;
	}

	@Override
	public String description() {
		return "coupon_event.remaining_stock을 1 감소시켜 재고 카운터 표류를 재현합니다.";
	}

	@Override
	@Transactional
	public InjectionResult inject(Long eventId) {
		int updated = jdbcTemplate.update(CORRUPT_SQL, new MapSqlParameterSource("eventId", eventId));
		if (updated == 0) {
			throw new ConsistencyCheckException(ErrorCode.INJECTION_TARGET_NOT_FOUND, "존재하지 않는 이벤트입니다. eventId=" + eventId);
		}
		return new InjectionResult(CHECK_NAME, eventId,
				String.format("이벤트 %d의 coupon_event.remaining_stock을 1 감소시켜 재고 카운터 표류를 만들었습니다.", eventId));
	}
}
