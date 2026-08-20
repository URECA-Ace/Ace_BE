package com.ace.consistency.batch;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.batch.infrastructure.item.ItemStream;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code ALL} 스코프 배치의 데이터 소스 역할을 하는 {@link ItemReader}.
 * coupon_event 테이블을 event_id 기준으로 keyset 페이징하며, 한 번 read()할 때마다
 * pageSize개의 event_id 묶음을 반환한다. 더 이상 읽을 데이터가 없으면 null을 반환해
 * Step 종료를 알린다.
 *
 * {@link ItemStream}을 함께 구현해 마지막으로 읽은 event_id({@code lastSeenId})를
 * {@link ExecutionContext}에 저장한다. Step이 실패 후 재시작되면 이 값을 복원해
 * 처음부터 다시 읽지 않고 이어서 읽을 수 있다.
 */
@RequiredArgsConstructor
public class EventIdPageReader implements ItemReader<List<Long>>, ItemStream {

	private static final String LAST_SEEN_ID_KEY = "eventIdPageReader.lastSeenId";

	private static final String QUERY = """
            SELECT event_id
            FROM coupon_event
            WHERE event_id > :lastSeenId 
            	AND created_at < :to
            ORDER BY event_id
            LIMIT :pageSize
            """;

	private final NamedParameterJdbcTemplate jdbcTemplate;
	private final int pageSize;
	private final LocalDateTime to;

	private long lastSeenId;

	@Override
	public void open(ExecutionContext executionContext) throws ItemStreamException {
		this.lastSeenId = executionContext.getLong(LAST_SEEN_ID_KEY, 0L);
	}

	@Override
	public List<Long> read() {
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("lastSeenId", lastSeenId)
				.addValue("pageSize", pageSize)
				.addValue("to", to);

		List<Long> eventIds = jdbcTemplate.queryForList(QUERY, params, Long.class);

		if (eventIds.isEmpty()) {
			return null; // Step 종료 신호
		}

		lastSeenId = eventIds.getLast();
		return eventIds;
	}

	@Override
	public void update(ExecutionContext executionContext) throws ItemStreamException {
		executionContext.putLong(LAST_SEEN_ID_KEY, lastSeenId);
	}
}