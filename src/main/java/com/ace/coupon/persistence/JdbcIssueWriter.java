package com.ace.coupon.persistence;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import com.ace.coupon.enums.CouponIssueStatus;
import com.ace.coupon.redis.CouponIssueRedisProperties;

// JdbcTemplate 기반 멱등 저장
@Component
public class JdbcIssueWriter implements IssueWriter {

	// ON DUPLICATE KEY UPDATE 를 쓰지 않는다
	// 어느 UNIQUE 가 걸렸는지 알려주지 않아 순번 중복까지 조용히 넘어갈 수 있기 때문
	private static final String INSERT_ISSUE = """
			INSERT INTO coupon_issue
			    (event_id, user_id, issue_sequence, request_id, status,
			     issued_at, valid_from, valid_to, created_at, message_id)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""";

	// FOR SHARE 필수: 일반 SELECT 는 트랜잭션 스냅샷을 읽어 방금 커밋한 승자 행이 안 보인다(REPEATABLE READ)
	private static final String SELECT_BY_REQUEST_ID = """
			SELECT issue_id FROM coupon_issue WHERE request_id = ? FOR SHARE
			""";

	// event_uid = requestId, uk_coupon_history_event_uid 가 이력 중복을 막는다
	private static final String INSERT_HISTORY = """
			INSERT INTO coupon_history
			    (issue_id, from_status, to_status, actor, reason,
			     occurred_at, recorded_at, event_uid)
			VALUES (?, NULL, ?, ?, ?, ?, ?, ?)
			ON DUPLICATE KEY UPDATE history_id = history_id
			""";

	private static final String ACTOR = "SYSTEM";
	private static final String REASON = "ISSUE_CONFIRMED";

	private final JdbcTemplate jdbcTemplate;
	private final ZoneId zoneId;

	public JdbcIssueWriter(JdbcTemplate jdbcTemplate, CouponIssueRedisProperties redisProperties) {
		this.jdbcTemplate = jdbcTemplate;
		this.zoneId = redisProperties.zoneId();
	}

	@Override
	public long write(IssueRecord record, CampaignMetadata metadata) {
		if (record == null || metadata == null) {
			throw new IllegalArgumentException("저장 입력과 회차 정보가 필요합니다.");
		}
		if (record.campaignId() != metadata.eventId()) {
			throw new IllegalArgumentException("저장 입력과 회차 정보가 일치하지 않습니다.");
		}

		// 판정 시각은 Redis 서버 시각
		// 여기서 now() 를 쓰면 인스턴스 2대의 시계 편차가 섞인다
		LocalDateTime decidedAt = LocalDateTime.ofInstant(record.decidedAt(), zoneId);
		LocalDateTime recordedAt = LocalDateTime.now(zoneId);

		long issueId = insertIssue(record, metadata, decidedAt, recordedAt);
		insertHistory(issueId, record, decidedAt, recordedAt);
		return issueId;
	}

	// 평범한 INSERT 후 중복이면 다시 봄
	// 정상 경로는 statement 한 개로 끝난다
	private long insertIssue(
			IssueRecord record,
			CampaignMetadata metadata,
			LocalDateTime decidedAt,
			LocalDateTime recordedAt) {

		KeyHolder keyHolder = new GeneratedKeyHolder();
		try {
			jdbcTemplate.update(connection -> {
				PreparedStatement statement =
						connection.prepareStatement(INSERT_ISSUE, Statement.RETURN_GENERATED_KEYS);
				statement.setLong(1, record.campaignId());
				statement.setLong(2, record.userId());
				statement.setInt(3, Math.toIntExact(record.issueSequence()));
				statement.setString(4, record.requestId().toString());
				statement.setString(5, CouponIssueStatus.ISSUED.name());
				statement.setObject(6, decidedAt);
				statement.setObject(7, decidedAt);
				statement.setObject(8, metadata.validTo(decidedAt));
				statement.setObject(9, recordedAt);
				if (record.messageId() == null) {
					statement.setNull(10, Types.VARCHAR);
				} else {
					statement.setString(10, record.messageId());
				}
				return statement;
			}, keyHolder);
		} catch (DuplicateKeyException exception) {
			return resolveExisting(record, exception);
		}

		Number key = keyHolder.getKey();
		if (key == null || key.longValue() <= 0) {
			throw new IllegalStateException("발급 식별자를 확인할 수 없습니다: " + record.requestId());
		}
		return key.longValue();
	}

	// 같은 requestId 의 재처리만 흡수, 나머지 UNIQUE 충돌은 표시
	private long resolveExisting(IssueRecord record, DuplicateKeyException cause) {
		Long issueId = jdbcTemplate.query(
				SELECT_BY_REQUEST_ID,
				resultSet -> resultSet.next() ? resultSet.getLong(1) : null,
				record.requestId().toString());

		if (issueId == null) {
			throw new IllegalStateException(
					"발급 UNIQUE 충돌 - 요청 %s (event_id=%d, user_id=%d, sequence=%d) 이(가) 기존 행과 충돌했습니다."
							.formatted(
									record.requestId(),
									record.campaignId(),
									record.userId(),
									record.issueSequence()),
					cause);
		}
		return issueId;
	}

	// 발생 시각(판정)과 기록 시각(저장)을 나눠 담기(둘의 차이가 저장 지연)
	private void insertHistory(
			long issueId,
			IssueRecord record,
			LocalDateTime decidedAt,
			LocalDateTime recordedAt) {

		jdbcTemplate.update(
				INSERT_HISTORY,
				issueId,
				CouponIssueStatus.ISSUED.name(),
				ACTOR,
				REASON,
				decidedAt,
				recordedAt,
				record.requestId().toString());
	}
}
