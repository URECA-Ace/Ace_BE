package com.ace.consistency.check;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** COUNT(*) OVER() 결과에서 전체 위반 건수와 제한된 샘플을 분리한다. */
record SampledViolationQueryResult(int violationCount, List<Map<String, Object>> sample) {

	private static final String TOTAL_COUNT_COLUMN = "total_violation_count";

	static SampledViolationQueryResult query(
			NamedParameterJdbcTemplate jdbcTemplate,
			String sql,
			MapSqlParameterSource params) {
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params);
		if (rows.isEmpty()) {
			return new SampledViolationQueryResult(0, List.of());
		}

		Object countValue = rows.getFirst().get(TOTAL_COUNT_COLUMN);
		if (!(countValue instanceof Number count)) {
			throw new IllegalStateException("Missing numeric " + TOTAL_COUNT_COLUMN + " in violation query result");
		}

		List<Map<String, Object>> sample = rows.stream()
				.map(row -> {
					Map<String, Object> sampleRow = new LinkedHashMap<>(row);
					sampleRow.remove(TOTAL_COUNT_COLUMN);
					return sampleRow;
				})
				.toList();
		return new SampledViolationQueryResult(count.intValue(), sample);
	}
}
