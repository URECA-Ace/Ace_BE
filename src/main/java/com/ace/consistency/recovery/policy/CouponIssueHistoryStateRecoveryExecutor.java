package com.ace.consistency.recovery.policy;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

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

import lombok.RequiredArgsConstructor;

/** NO_HISTORY + ISSUED 위반에 대해 최초 ISSUED History를 복원하는 실행기. */
@Component
@RequiredArgsConstructor
public class CouponIssueHistoryStateRecoveryExecutor {

	private static final Pattern UUID_PATTERN = Pattern.compile(
			"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

	private final RowLevelRecoveryRepository repository;

	/** 각 issue를 별도 물리 트랜잭션으로 처리하여 한 대상 실패가 다른 대상의 커밋을 되돌리지 않도록 한다. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public RecoveryOutcome recoverIssue(VerificationResultEntity target, long issueId) {
		try {
			IssueSnapshot issue = repository.findIssueForUpdate(issueId)
					.orElseThrow(() -> new IllegalStateException("발급 내역을 찾을 수 없습니다. issueId=" + issueId));
			Scope revalidationScope = Scope.ofEvent(issue.eventId());
			validateScope(target, issue);

			if (!isUuid(issue.requestId()) || issue.issuedAt() == null) {
				return failure(revalidationScope, issue, "초기 이력 복원에 필요한 requestId 또는 issuedAt이 유효하지 않습니다.");
			}

			int historyCount = repository.countHistories(issue.issueId());
			if ("ISSUED".equals(issue.status()) && issue.usedAt() == null && issue.canceledAt() == null
					&& repository.initialHistoryRestored(issue.issueId(), issue.requestId())) {
				return RecoveryOutcome.alreadyResolved(revalidationScope,
						detail(issue, issue.status(), historyCount), "최초 발급 이력이 이미 복원된 상태입니다.");
			}

			if (!"ISSUED".equals(issue.status()) || historyCount != 0) {
				return failure(revalidationScope, issue, "현재는 NO_HISTORY + ISSUED 위반이 아닙니다.");
			}
			if (issue.usedAt() != null || issue.canceledAt() != null) {
				return failure(revalidationScope, issue, "사용 또는 사용 취소 흔적이 있어 최초 발급 이력을 복원할 수 없습니다.");
			}
			if (repository.historyEventUidExists(issue.requestId())) {
				return failure(revalidationScope, issue, "초기 이력 event_uid가 이미 사용 중입니다.");
			}

			LocalDateTime recordedAt = LocalDateTime.now();
			repository.insertHistory(issue.issueId(), null, "ISSUED", "SYSTEM", "ISSUE_CONFIRMED",
					issue.issuedAt(), recordedAt, issue.requestId());
			if (!repository.initialHistoryRestored(issue.issueId(), issue.requestId())) {
				throw new IllegalStateException("초기 발급 이력 복구 결과를 확인할 수 없습니다.");
			}

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("issueId", issue.issueId());
			result.put("eventId", issue.eventId());
			result.put("violationType", "NO_HISTORY");
			result.put("before", detail(issue, "ISSUED", 0));
			result.put("after", detail(issue, "ISSUED", 1));
			return RecoveryOutcome.success(revalidationScope, result, "최초 발급 이력을 복원했습니다.");
		} catch (Exception ex) {
			if (TransactionSynchronizationManager.isActualTransactionActive()) {
				TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
			}
			return RecoveryOutcome.failure(fallbackScope(target), Map.of("issueId", issueId),
					"최초 발급 이력 복구 중 오류가 발생했습니다: " + ex.getMessage());
		}
	}

	private void validateScope(VerificationResultEntity target, IssueSnapshot issue) {
		if (target.getScopeType() == Scope.ScopeType.EVENT
				&& !Long.valueOf(issue.eventId()).equals(target.getEventId())) {
			throw new IllegalStateException("복구 대상이 검증 결과의 EVENT 범위에 포함되지 않습니다.");
		}
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
