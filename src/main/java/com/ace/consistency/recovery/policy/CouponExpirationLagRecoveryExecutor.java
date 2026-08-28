package com.ace.consistency.recovery.policy;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.ace.consistency.common.Scope;
import com.ace.consistency.entity.VerificationResultEntity;
import com.ace.consistency.recovery.RecoveryOutcome;
import com.ace.consistency.recovery.RowLevelRecoveryRepository;
import com.ace.consistency.recovery.RowLevelRecoveryRepository.IssueSnapshot;

/** EXPIRATION_BATCH_DELAY 위반에 대해 ISSUED 쿠폰을 안전하게 EXPIRED로 전환하는 실행기. */
@Component
public class CouponExpirationLagRecoveryExecutor {

	private static final Pattern UUID_PATTERN = Pattern.compile(
			"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

	private final RowLevelRecoveryRepository repository;
	private final long allowedDelayMillis;

	public CouponExpirationLagRecoveryExecutor(RowLevelRecoveryRepository repository,
			@Value("${consistency.expiration.allowed-delay-ms:120000}") long allowedDelayMillis) {
		this.repository = repository;
		this.allowedDelayMillis = allowedDelayMillis;
	}

	/** 각 issue를 별도 물리 트랜잭션으로 처리하여 부분 성공을 명확히 보장한다. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public RecoveryOutcome recoverIssue(VerificationResultEntity target, long issueId) {
		try {
			IssueSnapshot issue = repository.findIssueForUpdate(issueId)
					.orElseThrow(() -> new IllegalStateException("발급 내역을 찾을 수 없습니다. issueId=" + issueId));
			Scope revalidationScope = Scope.ofEvent(issue.eventId());
			validateScope(target, issue);

			if ("EXPIRED".equals(issue.status()) && issue.usedAt() == null
					&& repository.hasExpirationRecoveryHistory(issue.issueId())) {
				return RecoveryOutcome.alreadyResolved(revalidationScope,
						detail(issue, "EXPIRED", repository.countHistories(issue.issueId())),
						"만료 지연 복구가 이미 반영된 상태입니다.");
			}

			LocalDateTime recoveryCheckedAt = LocalDateTime.now();
			if (!"ISSUED".equals(issue.status()) || issue.usedAt() != null || issue.validTo() == null) {
				return failure(revalidationScope, issue, "현재는 EXPIRATION_BATCH_DELAY 위반이 아닙니다.");
			}
			if (!issue.validTo().plusNanos(Math.multiplyExact(allowedDelayMillis, 1_000_000L))
					.isBefore(recoveryCheckedAt)) {
				return failure(revalidationScope, issue, "만료 처리 허용 지연 시간이 지나지 않았습니다.");
			}
			if (issue.createdAt() == null || issue.createdAt().isAfter(recoveryCheckedAt)) {
				return failure(revalidationScope, issue, "복구 기준 시각에 아직 생성되지 않은 발급 건입니다.");
			}
			if (!hasValidStructuralFields(issue)) {
				return failure(revalidationScope, issue, "발급 구조가 유효하지 않아 만료 복구를 실행할 수 없습니다.");
			}

			int beforeHistoryCount = repository.countHistories(issue.issueId());
			if (!repository.expireIfStillIssued(issue.issueId())) {
				return failure(revalidationScope, issue, "복구 중 발급 상태가 변경되었습니다.");
			}
			String eventUid = UUID.randomUUID().toString();
			repository.insertHistory(issue.issueId(), "ISSUED", "EXPIRED", "SYSTEM",
					"CONSISTENCY_RECOVERY_EXPIRATION", issue.validTo(), recoveryCheckedAt, eventUid);
			if (!repository.expirationRecovered(issue.issueId(), eventUid)) {
				throw new IllegalStateException("만료 복구 결과를 확인할 수 없습니다.");
			}

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("issueId", issue.issueId());
			result.put("eventId", issue.eventId());
			result.put("violationType", "EXPIRATION_BATCH_DELAY");
			result.put("before", detail(issue, "ISSUED", beforeHistoryCount));
			result.put("after", detail(issue, "EXPIRED", beforeHistoryCount + 1));
			return RecoveryOutcome.success(revalidationScope, result, "만료 지연 쿠폰을 EXPIRED 상태로 변경했습니다.");
		} catch (Exception ex) {
			if (TransactionSynchronizationManager.isActualTransactionActive()) {
				TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
			}
			return RecoveryOutcome.failure(fallbackScope(target), Map.of("issueId", issueId),
					"만료 지연 복구 중 오류가 발생했습니다: " + ex.getMessage());
		}
	}

	private void validateScope(VerificationResultEntity target, IssueSnapshot issue) {
		if (target.getScopeType() == Scope.ScopeType.EVENT
				&& !Long.valueOf(issue.eventId()).equals(target.getEventId())) {
			throw new IllegalStateException("복구 대상이 검증 결과의 EVENT 범위에 포함되지 않습니다.");
		}
		if (target.getScopeType() == Scope.ScopeType.AS_OF_RANGE
				&& (issue.validTo() == null || issue.validTo().isBefore(target.getScopeFrom())
				|| !issue.validTo().isBefore(target.getScopeTo()))) {
			throw new IllegalStateException("복구 대상이 검증 결과의 AS_OF_RANGE 범위에 포함되지 않습니다.");
		}
	}

	private boolean hasValidStructuralFields(IssueSnapshot issue) {
		return issue.userId() != null && issue.issueSequence() != null && issue.issueSequence() > 0
				&& isUuid(issue.requestId()) && (issue.messageId() == null || isUuid(issue.messageId()))
				&& issue.issuedAt() != null && issue.validFrom() != null && issue.createdAt() != null
				&& !issue.issuedAt().isAfter(issue.validFrom())
				&& issue.validFrom().isBefore(issue.validTo())
				&& !issue.createdAt().isBefore(issue.issuedAt());
	}

	private RecoveryOutcome failure(Scope scope, IssueSnapshot issue, String message) {
		return RecoveryOutcome.failure(scope, detail(issue, issue.status(), repository.countHistories(issue.issueId())), message);
	}

	private Map<String, Object> detail(IssueSnapshot issue, String status, int historyCount) {
		Map<String, Object> detail = new LinkedHashMap<>();
		detail.put("issueId", issue.issueId());
		detail.put("eventId", issue.eventId());
		detail.put("status", status);
		detail.put("historyCount", historyCount);
		return detail;
	}

	private Scope fallbackScope(VerificationResultEntity target) {
		return switch (target.getScopeType()) {
			case EVENT -> Scope.ofEvent(target.getEventId());
			case AS_OF_RANGE -> Scope.ofAsOfRange(target.getScopeFrom(), target.getScopeTo());
			case ALL -> Scope.all(LocalDateTime.now());
		};
	}

	private boolean isUuid(String value) {
		return value != null && UUID_PATTERN.matcher(value).matches();
	}
}
