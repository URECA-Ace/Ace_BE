package com.ace.consistency.recovery.policy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.ace.consistency.common.Scope;
import com.ace.consistency.recovery.RecoveryOutcome;
import com.ace.coupon.entity.CouponHistory;
import com.ace.coupon.entity.CouponIssue;
import com.ace.coupon.enums.CouponIssueStatus;
import com.ace.coupon.repository.CouponEventRepository;
import com.ace.coupon.repository.CouponHistoryRepository;
import com.ace.coupon.repository.CouponIssueRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * EventLog (StateMachine, TimeSync) 정합성 복구를 개별 이벤트 단위로 실행하는 Executor.
 * 
 * 여러 이벤트를 한 번에 복구할 때(ALL 스코프 등), 루프 안에서 한 이벤트의 복구 실패(예외)가
 * 이미 성공한 다른 이벤트의 복구 내역이나 바깥쪽 트랜잭션(RecoveryResult 기록)을 롤백시키지
 * 않도록, 각 이벤트별 복구를 REQUIRES_NEW 물리 트랜잭션으로 격리한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EventLogRecoveryExecutor {

	private static final Duration THRESHOLD = Duration.ofSeconds(1);
	private static final List<CouponIssueStatus> SYNCABLE_STATUSES = List.of(CouponIssueStatus.ISSUED, CouponIssueStatus.USED);

	private final CouponIssueRepository couponIssueRepository;
	private final CouponHistoryRepository couponHistoryRepository;
	private final CouponEventRepository couponEventRepository;

	/**
	 * StateMachineConsistencyRecoveryPolicy의 이벤트 단위 복구
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public RecoveryOutcome restoreStateMachine(Long eventId) {
		try {
			return recoverBrokenChains(eventId);
		} catch (Exception ex) {
			log.error("상태 머신 정합성 복구 중 오류가 발생했습니다. eventId={}", eventId, ex);
			if (TransactionSynchronizationManager.isActualTransactionActive()) {
				TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
			}
			return RecoveryOutcome.failure(Scope.ofEvent(eventId), Map.of(),
					"상태 머신 복구 중 오류가 발생했습니다: " + ex.getMessage());
		}
	}

	private RecoveryOutcome recoverBrokenChains(Long eventId) {
		couponEventRepository.findByIdForUpdate(eventId)
				.orElseThrow(() -> new IllegalStateException("존재하지 않는 이벤트입니다. eventId=" + eventId));

		Map<Long, List<CouponHistory>> chainsByIssueId = groupByIssue(
				couponHistoryRepository.findAllByCouponEventIdOrderByIssueAndTime(eventId));

		List<Long> recoveredIssueIds = new ArrayList<>();
		List<Long> notEligibleIssueIds = new ArrayList<>();

		for (Map.Entry<Long, List<CouponHistory>> entry : chainsByIssueId.entrySet()) {
			Long issueId = entry.getKey();
			List<CouponHistory> chain = entry.getValue();

			Optional<Integer> breakIndex = findBreakIndex(chain);
			if (breakIndex.isEmpty()) {
				continue; 
			}
			int index = breakIndex.get();
			if (index == 0) {
				notEligibleIssueIds.add(issueId); 
				continue;
			}

			List<CouponHistory> tail = chain.subList(index, chain.size());
			boolean containsUsedOrExpired = tail.stream()
					.anyMatch(h -> h.getToStatus() == CouponIssueStatus.USED || h.getToStatus() == CouponIssueStatus.EXPIRED);
			if (containsUsedOrExpired) {
				notEligibleIssueIds.add(issueId);
				continue;
			}

			CouponIssueStatus revertTo = chain.get(index - 1).getToStatus();
			List<Long> deleteIds = tail.stream().map(CouponHistory::getId).toList();
			couponHistoryRepository.deleteAllByIdInBatch(deleteIds);

			CouponIssue lockedIssue = couponIssueRepository.findByIdForUpdate(issueId)
					.orElseThrow(() -> new IllegalStateException("존재하지 않는 발급 건입니다. issueId=" + issueId));
			lockedIssue.restoreStatus(revertTo);

			recoveredIssueIds.add(issueId);
		}

		Map<String, Object> detail = Map.of(
				"eventId", eventId,
				"recoveredIssueIds", recoveredIssueIds,
				"notEligibleIssueIds", notEligibleIssueIds);

		if (!notEligibleIssueIds.isEmpty()) {
			return RecoveryOutcome.failure(Scope.ofEvent(eventId), detail,
					String.format("이벤트 %d의 체인 붕괴 %d건 중 %d건만 복구했습니다. %d건은 삭제 범위에 USED/EXPIRED가 포함되어 있거나 "
									+ "최초 이력이 손상되어 자동 복구하지 못했으므로 관리자 확인이 필요합니다.",
								eventId, recoveredIssueIds.size() + notEligibleIssueIds.size(),
								recoveredIssueIds.size(), notEligibleIssueIds.size()));
		}
		if (recoveredIssueIds.isEmpty()) {
			return RecoveryOutcome.success(Scope.ofEvent(eventId), detail,
					String.format("이벤트 %d는 이력 체인 붕괴 상태가 아닙니다.", eventId));
		}
		return RecoveryOutcome.success(Scope.ofEvent(eventId), detail,
				String.format("이벤트 %d의 체인 붕괴 %d건을 모두 복구했습니다.", eventId, recoveredIssueIds.size()));
	}

	private Map<Long, List<CouponHistory>> groupByIssue(List<CouponHistory> histories) {
		Map<Long, List<CouponHistory>> grouped = new LinkedHashMap<>();
		for (CouponHistory history : histories) {
			grouped.computeIfAbsent(history.getCouponIssue().getId(), key -> new ArrayList<>()).add(history);
		}
		return grouped;
	}

	private Optional<Integer> findBreakIndex(List<CouponHistory> chain) {
		CouponIssueStatus prevToStatus = null;
		for (int i = 0; i < chain.size(); i++) {
			CouponHistory row = chain.get(i);
			if (i == 0) {
				if (row.getFromStatus() != null || row.getToStatus() != CouponIssueStatus.ISSUED) {
					return Optional.of(0);
				}
			} else if (row.getFromStatus() != prevToStatus) {
				return Optional.of(i);
			}
			prevToStatus = row.getToStatus();
		}
		return Optional.empty();
	}

	/**
	 * IssueHistoryTimeSyncConsistencyRecoveryPolicy의 이벤트 단위 복구
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public RecoveryOutcome syncTimeHistory(Long eventId) {
		try {
			return syncTimestamps(eventId);
		} catch (Exception ex) {
			log.error("연동 도메인 시간 동기화 복구 중 오류가 발생했습니다. eventId={}", eventId, ex);
			if (TransactionSynchronizationManager.isActualTransactionActive()) {
				TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
			}
			return RecoveryOutcome.failure(Scope.ofEvent(eventId), Map.of(),
					"시간 동기화 복구 중 오류가 발생했습니다: " + ex.getMessage());
		}
	}

	private RecoveryOutcome syncTimestamps(Long eventId) {
		couponEventRepository.findByIdForUpdate(eventId)
				.orElseThrow(() -> new IllegalStateException("존재하지 않는 이벤트입니다. eventId=" + eventId));

		List<CouponIssue> candidates = couponIssueRepository.findByCouponEvent_IdAndStatusIn(eventId, SYNCABLE_STATUSES);

		List<Long> patchedIssueIds = new ArrayList<>();
		List<Long> notEligibleIssueIds = new ArrayList<>();

		for (CouponIssue candidate : candidates) {
			List<CouponHistory> chain = couponHistoryRepository.findAllByCouponIssue_IdOrderByOccurredAtAsc(candidate.getId());
			if (chain.isEmpty()) {
				continue;
			}
			CouponHistory latest = chain.getLast();

			if (candidate.getStatus() == CouponIssueStatus.ISSUED && latest.getFromStatus() != null) {
				notEligibleIssueIds.add(candidate.getId()); 
				continue;
			}

			LocalDateTime issueTime = candidate.getStatus() == CouponIssueStatus.USED
					? candidate.getUsedAt() : candidate.getIssuedAt();
			LocalDateTime historyTime = latest.getOccurredAt();

			boolean needsPatch = issueTime == null
					|| (issueTime.isBefore(historyTime)
						&& Duration.between(issueTime, historyTime).abs().compareTo(THRESHOLD) > 0);
			if (!needsPatch) {
				continue;
			}

			CouponIssue lockedIssue = couponIssueRepository.findByIdForUpdate(candidate.getId())
					.orElseThrow(() -> new IllegalStateException("존재하지 않는 발급 건입니다. issueId=" + candidate.getId()));
			if (lockedIssue.getStatus() == CouponIssueStatus.USED) {
				lockedIssue.syncUsedAt(historyTime);
			} else {
				lockedIssue.syncIssuedAt(historyTime);
			}
			patchedIssueIds.add(candidate.getId());
		}

		Map<String, Object> detail = Map.of(
				"eventId", eventId,
				"patchedIssueIds", patchedIssueIds,
				"notEligibleIssueIds", notEligibleIssueIds);

		if (!notEligibleIssueIds.isEmpty()) {
			return RecoveryOutcome.failure(Scope.ofEvent(eventId), detail,
					String.format("이벤트 %d의 시간 불일치 중 %d건은 최초 발급이 아닌 재진입 케이스라 자동 복구하지 못했습니다. 관리자 확인이 필요합니다.",
							eventId, notEligibleIssueIds.size()));
		}
		if (patchedIssueIds.isEmpty()) {
			return RecoveryOutcome.success(Scope.ofEvent(eventId), detail,
					String.format("이벤트 %d는 시간 동기화가 필요한 발급 건이 없습니다.", eventId));
		}
		return RecoveryOutcome.success(Scope.ofEvent(eventId), detail,
				String.format("이벤트 %d의 시간 불일치 %d건을 history 기준으로 동기화했습니다.", eventId, patchedIssueIds.size()));
	}
}
