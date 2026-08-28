package com.ace.consistency.recovery.policy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;

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

	@Value("${consistency.recovery.chunk-size:500}")
	private int chunkSize;

	@Lazy
	@Autowired
	private EventLogRecoveryExecutor self;

	/**
	 * StateMachineConsistencyRecoveryPolicy의 이벤트 단위 복구
	 */
	@Transactional
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

		// 1. 대상 Issue 목록 식별 (조회 시점 기준)
		Map<Long, List<CouponHistory>> chainsByIssueId = groupByIssue(
				couponHistoryRepository.findAllByCouponEventIdOrderByIssueAndTime(eventId));
		List<Long> allIssueIds = new ArrayList<>(chainsByIssueId.keySet());

		List<Long> recoveredIssueIds = new ArrayList<>();
		List<Long> notEligibleIssueIds = new ArrayList<>();

		// 2. Chunk 단위로 분할하여 개별 트랜잭션 처리
		for (int i = 0; i < allIssueIds.size(); i += chunkSize) {
			List<Long> chunk = allIssueIds.subList(i, Math.min(allIssueIds.size(), i + chunkSize));
			try {
				ChunkResult result = self.processStateMachineChunk(chunk);
				recoveredIssueIds.addAll(result.recovered());
				notEligibleIssueIds.addAll(result.notEligible());
			} catch (Exception ex) {
				log.error("상태 머신 복구 청크 처리 중 예외 발생. eventId={}, chunkIndex={}", eventId, i, ex);
			}
		}

		Map<String, Object> detail = Map.of(
				"eventId", eventId,
				"recoveredIssueIds", recoveredIssueIds,
				"notEligibleIssueIds", notEligibleIssueIds);

		if (!notEligibleIssueIds.isEmpty()) {
			return RecoveryOutcome.failure(Scope.ofEvent(eventId), detail,
					String.format("이벤트 %d의 체인 붕괴 대상 중 %d건만 복구했습니다. %d건은 삭제 범위에 USED/EXPIRED가 포함되어 있거나 "
									+ "최초 이력이 손상되어 자동 복구하지 못했으므로 관리자 확인이 필요합니다.",
								eventId, recoveredIssueIds.size(), notEligibleIssueIds.size()));
		}
		if (recoveredIssueIds.isEmpty()) {
			return RecoveryOutcome.success(Scope.ofEvent(eventId), detail,
					String.format("이벤트 %d는 이력 체인 붕괴 상태가 아닙니다.", eventId));
		}
		return RecoveryOutcome.success(Scope.ofEvent(eventId), detail,
				String.format("이벤트 %d의 체인 붕괴 %d건을 모두 복구했습니다.", eventId, recoveredIssueIds.size()));
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public ChunkResult processStateMachineChunk(List<Long> chunkIssueIds) {
		List<Long> recoveredIssueIds = new ArrayList<>();
		List<Long> notEligibleIssueIds = new ArrayList<>();

		for (Long issueId : chunkIssueIds) {
			// 1. Issue 락 먼저 획득 (유저 트랜잭션 차단)
			CouponIssue lockedIssue = couponIssueRepository.findByIdForUpdate(issueId)
					.orElseThrow(() -> new IllegalStateException("존재하지 않는 발급 건입니다. issueId=" + issueId));

			// 2. 락 획득 후 최신 History 재조회 (안전한 데이터 뷰 확보)
			List<CouponHistory> latestChain = couponHistoryRepository.findAllByCouponIssue_IdOrderByOccurredAtAsc(issueId);

			Optional<Integer> breakIndex = findBreakIndex(latestChain);
			if (breakIndex.isEmpty()) {
				continue; // 대기 중 외부 트랜잭션이 이미 해결했을 수 있음
			}
			int index = breakIndex.get();
			if (index == 0) {
				notEligibleIssueIds.add(issueId); 
				continue;
			}

			List<CouponHistory> tail = latestChain.subList(index, latestChain.size());
			boolean containsUsedOrExpired = tail.stream()
					.anyMatch(h -> h.getToStatus() == CouponIssueStatus.USED || h.getToStatus() == CouponIssueStatus.EXPIRED);
			if (containsUsedOrExpired) {
				notEligibleIssueIds.add(issueId);
				continue;
			}

			List<CouponHistory> validChain = latestChain.subList(0, index);
			CouponIssueStatus revertTo = validChain.getLast().getToStatus();
			LocalDateTime revertUsedAt = null;
			LocalDateTime revertCanceledAt = null;

			for (CouponHistory h : validChain) {
				if (h.getToStatus() == CouponIssueStatus.USED) {
					revertUsedAt = h.getOccurredAt();
					revertCanceledAt = null;
				} else if (h.getToStatus() == CouponIssueStatus.ISSUED && h.getFromStatus() == CouponIssueStatus.USED) {
					revertUsedAt = null;
					revertCanceledAt = h.getOccurredAt();
				} else if (h.getToStatus() == CouponIssueStatus.CANCELED) {
					revertCanceledAt = h.getOccurredAt();
				} else if (h.getToStatus() == CouponIssueStatus.ISSUED && h.getFromStatus() == null) {
					revertUsedAt = null;
					revertCanceledAt = null;
				}
			}

			List<Long> deleteIds = tail.stream().map(CouponHistory::getId).toList();
			couponHistoryRepository.deleteAllByIdInBatch(deleteIds);

			lockedIssue.restoreStatus(revertTo, revertUsedAt, revertCanceledAt);

			recoveredIssueIds.add(issueId);
		}
		return new ChunkResult(recoveredIssueIds, notEligibleIssueIds);
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
	@Transactional
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
		List<Long> candidateIds = candidates.stream().map(CouponIssue::getId).toList();

		List<Long> patchedIssueIds = new ArrayList<>();
		List<Long> notEligibleIssueIds = new ArrayList<>();

		// Chunk 단위로 분할하여 개별 트랜잭션 처리
		for (int i = 0; i < candidateIds.size(); i += chunkSize) {
			List<Long> chunk = candidateIds.subList(i, Math.min(candidateIds.size(), i + chunkSize));
			try {
				ChunkResult result = self.processTimeSyncChunk(chunk);
				patchedIssueIds.addAll(result.recovered());
				notEligibleIssueIds.addAll(result.notEligible());
			} catch (Exception ex) {
				log.error("시간 동기화 복구 청크 처리 중 예외 발생. eventId={}, chunkIndex={}", eventId, i, ex);
			}
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

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public ChunkResult processTimeSyncChunk(List<Long> chunkIssueIds) {
		List<Long> patchedIssueIds = new ArrayList<>();
		List<Long> notEligibleIssueIds = new ArrayList<>();

		for (Long issueId : chunkIssueIds) {
			// 1. Issue 락 먼저 획득 (유저 트랜잭션 차단)
			CouponIssue lockedIssue = couponIssueRepository.findByIdForUpdate(issueId)
					.orElseThrow(() -> new IllegalStateException("존재하지 않는 발급 건입니다. issueId=" + issueId));

			// 2. 락 획득 후 최신 History 재조회
			List<CouponHistory> chain = couponHistoryRepository.findAllByCouponIssue_IdOrderByOccurredAtAsc(issueId);
			if (chain.isEmpty()) {
				continue;
			}
			CouponHistory latest = chain.getLast();

			if (lockedIssue.getStatus() == CouponIssueStatus.ISSUED && latest.getFromStatus() != null) {
				notEligibleIssueIds.add(issueId); 
				continue;
			}

			LocalDateTime issueTime = lockedIssue.getStatus() == CouponIssueStatus.USED
					? lockedIssue.getUsedAt() : lockedIssue.getIssuedAt();
			LocalDateTime historyTime = latest.getOccurredAt();

			boolean needsPatch = issueTime == null
					|| (issueTime.isBefore(historyTime)
						&& Duration.between(issueTime, historyTime).abs().compareTo(THRESHOLD) > 0);
			if (!needsPatch) {
				continue;
			}

			if (lockedIssue.getStatus() == CouponIssueStatus.USED) {
				lockedIssue.syncUsedAt(historyTime);
			} else {
				lockedIssue.syncIssuedAt(historyTime);
			}
			patchedIssueIds.add(issueId);
		}
		return new ChunkResult(patchedIssueIds, notEligibleIssueIds);
	}

	public record ChunkResult(List<Long> recovered, List<Long> notEligible) {}
}
