package com.ace.consistency.recovery;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public class RowLevelRecoveryRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public RowLevelRecoveryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public Optional<IssueSnapshot> findIssueForUpdate(long issueId) {
		String sql = """
				SELECT issue_id, event_id, user_id, issue_sequence, request_id, message_id, status,
				       issued_at, valid_from, valid_to, used_at, canceled_at, created_at
				FROM coupon_issue
				WHERE issue_id = :issueId
				FOR UPDATE
				""";
		return jdbcTemplate.query(sql, new MapSqlParameterSource("issueId", issueId), rs ->
				rs.next() ? Optional.of(new IssueSnapshot(
						rs.getLong("issue_id"), rs.getLong("event_id"), rs.getObject("user_id", Long.class),
						rs.getObject("issue_sequence", Integer.class), rs.getString("request_id"),
						rs.getString("message_id"), rs.getString("status"),
						rs.getObject("issued_at", LocalDateTime.class),
						rs.getObject("valid_from", LocalDateTime.class),
						rs.getObject("valid_to", LocalDateTime.class),
						rs.getObject("used_at", LocalDateTime.class),
						rs.getObject("canceled_at", LocalDateTime.class),
						rs.getObject("created_at", LocalDateTime.class))) : Optional.empty());
	}

	public int countHistories(long issueId) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM coupon_history WHERE issue_id = :issueId",
				new MapSqlParameterSource("issueId", issueId), Integer.class);
		return count == null ? 0 : count;
	}

	public boolean historyEventUidExists(String eventUid) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM coupon_history WHERE event_uid = :eventUid",
				new MapSqlParameterSource("eventUid", eventUid), Integer.class);
		return count != null && count > 0;
	}

	public void insertHistory(long issueId, String fromStatus, String toStatus,
			String actor, String reason, LocalDateTime occurredAt,
			LocalDateTime recordedAt, String eventUid) {
		jdbcTemplate.update("""
				INSERT INTO coupon_history(issue_id, from_status, to_status, actor, reason,
				 occurred_at, recorded_at, event_uid)
				VALUES (:issueId, :fromStatus, :toStatus, :actor, :reason,
				 :occurredAt, :recordedAt, :eventUid)
				""", new MapSqlParameterSource()
				.addValue("issueId", issueId)
				.addValue("fromStatus", fromStatus)
				.addValue("toStatus", toStatus)
				.addValue("actor", actor)
				.addValue("reason", reason)
				.addValue("occurredAt", occurredAt)
				.addValue("recordedAt", recordedAt)
				.addValue("eventUid", eventUid));
	}

	public boolean expireIfStillIssued(long issueId) {
		int updated = jdbcTemplate.update("""
				UPDATE coupon_issue
				SET status = 'EXPIRED'
				WHERE issue_id = :issueId AND status = 'ISSUED' AND used_at IS NULL
				""", new MapSqlParameterSource("issueId", issueId));
		return updated == 1;
	}

	public boolean initialHistoryRestored(long issueId, String eventUid) {
		Integer count = jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM coupon_history
				WHERE issue_id = :issueId AND from_status IS NULL AND to_status = 'ISSUED'
				  AND event_uid = :eventUid
				""", new MapSqlParameterSource()
				.addValue("issueId", issueId)
				.addValue("eventUid", eventUid), Integer.class);
		return count != null && count == 1;
	}

	public boolean expirationRecovered(long issueId, String eventUid) {
		Integer count = jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM coupon_issue ci
				JOIN coupon_history h ON h.issue_id = ci.issue_id
				WHERE ci.issue_id = :issueId AND ci.status = 'EXPIRED' AND ci.used_at IS NULL
				  AND h.from_status = 'ISSUED' AND h.to_status = 'EXPIRED'
				  AND h.event_uid = :eventUid
				""", new MapSqlParameterSource()
				.addValue("issueId", issueId)
				.addValue("eventUid", eventUid), Integer.class);
		return count != null && count == 1;
	}

	public boolean hasExpirationRecoveryHistory(long issueId) {
		Integer count = jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM coupon_history
				WHERE issue_id = :issueId
				  AND from_status = 'ISSUED' AND to_status = 'EXPIRED'
				  AND actor = 'SYSTEM' AND reason = 'CONSISTENCY_RECOVERY_EXPIRATION'
				""", new MapSqlParameterSource("issueId", issueId), Integer.class);
		return count != null && count > 0;
	}

	public record IssueSnapshot(
			long issueId,
			long eventId,
			Long userId,
			Integer issueSequence,
			String requestId,
			String messageId,
			String status,
			LocalDateTime issuedAt,
			LocalDateTime validFrom,
			LocalDateTime validTo,
			LocalDateTime usedAt,
			LocalDateTime canceledAt,
			LocalDateTime createdAt) {
	}
}
