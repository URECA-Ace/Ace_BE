package com.ace.consistency.recovery.policy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.ace.common.ErrorCode;
import com.ace.common.exception.ConsistencyCheckException;
import com.ace.consistency.common.Scope;
import com.ace.consistency.entity.VerificationResultEntity;
import com.ace.consistency.recovery.RecoveryOutcome;
import com.ace.consistency.recovery.enums.RecoveryAction;
import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.entity.CouponHistory;
import com.ace.coupon.entity.CouponIssue;
import com.ace.coupon.enums.CouponIssueStatus;
import com.ace.coupon.repository.CouponEventRepository;
import com.ace.coupon.repository.CouponHistoryRepository;
import com.ace.coupon.repository.CouponIssueRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * StockConsistencyCheck(FAIL) 위반에 대한 복구 정책.
 *
 * 위반 원인은 두 가지이고, 각각 다른 RecoveryAction으로 대응한다.
 * - STOCK_RECONCILE_COUNTER: coupon_issue(원본)의 실제 활성 발급 건수와 coupon_event에
 *   캐시된 카운터가 어긋난 "카운터 표류". coupon_issue는 건드리지 않고 카운터만 다시 계산한다.
 * - STOCK_REVOKE_EXCESS_ISSUANCE: 실제 활성 발급 건수가 total_stock을 넘어선 "진짜 초과발급".
 *   가장 최근에 발급된 ISSUED 건부터 초과분만큼 CANCELED로 되돌려 슬롯을 반납한다.
 *
 * target이 ALL 스코프여도(여러 이벤트를 한 번에 검증한 배치 결과) 이 정책은 target에서 직접
 * 위반 이벤트 목록을 뽑아내 이벤트마다 복구를 수행하고, 이벤트별 RecoveryOutcome을 모아 리스트로
 * 반환한다. 한 이벤트의 복구 실패가 다른 이벤트의 복구를 막지 않도록 이벤트 단위로 개별 처리한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StockConsistencyRecoveryPolicy implements ConsistencyRecoveryPolicy {

	private static final List<CouponIssueStatus> ACTIVE_STATUSES =
			List.of(CouponIssueStatus.ISSUED, CouponIssueStatus.USED, CouponIssueStatus.EXPIRED);

	private static final String REVOKE_ACTOR = "SYSTEM_STOCK_RECOVERY";
	private static final String REVOKE_REASON = "STOCK_OVER_ISSUANCE_AUTO_REVOKE";

	private final CouponEventRepository couponEventRepository;
	private final CouponIssueRepository couponIssueRepository;
	private final CouponHistoryRepository couponHistoryRepository;

	@Override
	public String checkName() {
		return "StockConsistencyCheck";
	}

	@Override
	public List<RecoveryAction> availableActions() {
		return List.of(RecoveryAction.STOCK_RECONCILE_COUNTER, RecoveryAction.STOCK_REVOKE_EXCESS_ISSUANCE);
	}

	@Override
	@Transactional
	public List<RecoveryOutcome> recover(VerificationResultEntity target, RecoveryAction action) {
		List<Long> eventIds = resolveEventIds(target);
		List<RecoveryOutcome> outcomes = new ArrayList<>();
		for (Long eventId : eventIds) {
			outcomes.add(recoverOneEvent(eventId, action));
		}
		return outcomes;
	}

	private RecoveryOutcome recoverOneEvent(Long eventId, RecoveryAction action) {
		try {
			return switch (action) {
				case STOCK_RECONCILE_COUNTER -> reconcileCounter(eventId);
				case STOCK_REVOKE_EXCESS_ISSUANCE -> revokeExcessIssuance(eventId);
				default -> RecoveryOutcome.failure(Scope.ofEvent(eventId), Map.of(),
						"StockConsistencyCheck는 STOCK_RECONCILE_COUNTER 또는 STOCK_REVOKE_EXCESS_ISSUANCE 액션이 필요합니다.");
			};
		} catch (Exception ex) {
			log.error("재고 정합성 복구 중 오류가 발생했습니다. eventId={}, action={}", eventId, action, ex);
			if (TransactionSynchronizationManager.isActualTransactionActive()) {
				TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
			}
			return RecoveryOutcome.failure(Scope.ofEvent(eventId), Map.of(),
					"재고 복구 중 오류가 발생했습니다: " + ex.getMessage());
		}
	}

	/**
	 * EVENT 스코프는 target 자체가 이벤트를 특정한다. ALL 스코프는 target만으로 대상을 알 수
	 * 없으므로, 그 체크를 실행했을 때 저장해둔 diffDetail.sample(위반 건 목록)에서 eventId를
	 * 뽑아 위반 이벤트 목록을 복원한다. sample은 SAMPLE_LIMIT 없이 위반 전체를 담고 있으므로
	 * 이 목록이 곧 실제 위반 이벤트 전체와 같다.
	 */
	@SuppressWarnings("unchecked")
	private List<Long> resolveEventIds(VerificationResultEntity target) {
		if (target.getScopeType() == Scope.ScopeType.EVENT) {
			return List.of(target.getEventId());
		}

		List<Map<String, Object>> sample = (List<Map<String, Object>>) target.getDiffDetail().get("sample");
		if (sample == null || sample.isEmpty()) {
			throw new ConsistencyCheckException(ErrorCode.RECOVERY_TARGET_EVENTS_NOT_FOUND);
		}

		return sample.stream()
				.map(row -> ((Number) row.get("eventId")).longValue())
				.distinct()
				.toList();
	}

	/**
	 * 카운터 표류 복구. coupon_issue가 실제 정확한 값이라는 전제 하에 coupon_event의
	 * issued_quantity/remaining_stock을 실제 활성 발급 건수 기준으로 재계산한다.
	 * 실제 활성 발급 건수가 total_stock을 초과한 상태(진짜 초과발급)에서는 재계산 대신
	 * STOCK_REVOKE_EXCESS_ISSUANCE로 초과분을 먼저 회수해야 하므로 실패 처리한다.
	 */
	private RecoveryOutcome reconcileCounter(Long eventId) {
		CouponEvent couponEvent = couponEventRepository.findByIdForUpdate(eventId)
				.orElseThrow(() -> new IllegalStateException("존재하지 않는 이벤트입니다. eventId=" + eventId));

		long actualActiveCount = couponIssueRepository.countByCouponEvent_IdAndStatusIn(eventId, ACTIVE_STATUSES);
		if (actualActiveCount > couponEvent.getTotalStock()) {
			return RecoveryOutcome.failure(Scope.ofEvent(eventId), Map.of(),
					String.format("이벤트 %d는 실제 발급 건수(%d건)가 총 재고(%d건)를 초과한 상태입니다. "
									+ "STOCK_REVOKE_EXCESS_ISSUANCE로 초과발급 회수가 선행되어야 합니다.",
							eventId, actualActiveCount, couponEvent.getTotalStock()));
		}

		int beforeIssuedQuantity = couponEvent.getIssuedQuantity();
		int beforeRemainingStock = couponEvent.getRemainingStock();

		couponEvent.reconcileStock((int) actualActiveCount, LocalDateTime.now());

		Map<String, Object> detail = Map.of(
				"eventId", eventId,
				"beforeIssuedQuantity", beforeIssuedQuantity,
				"beforeRemainingStock", beforeRemainingStock,
				"afterIssuedQuantity", couponEvent.getIssuedQuantity(),
				"afterRemainingStock", couponEvent.getRemainingStock());

		return RecoveryOutcome.success(Scope.ofEvent(eventId), detail,
				String.format("이벤트 %d의 재고 카운터를 실제 발급 건수(%d건)에 맞춰 재계산했습니다.", eventId, actualActiveCount));
	}

	/**
	 * 초과발급 자동 회수. 총 재고를 초과한 만큼, 가장 최근에 발급된 건부터 ISSUED 상태인 것만
	 * CANCELED로 전이시켜 슬롯을 반납한다. 이미 USED/EXPIRED로 전이된 건은 되돌리지 않고
	 * 회수 불가 목록으로 남겨 사후에 관리자가 확인하도록 한다.
	 */
	private RecoveryOutcome revokeExcessIssuance(Long eventId) {
		CouponEvent couponEvent = couponEventRepository.findByIdForUpdate(eventId)
				.orElseThrow(() -> new IllegalStateException("존재하지 않는 이벤트입니다. eventId=" + eventId));

		List<CouponIssue> activeIssues = couponIssueRepository
				.findByCouponEvent_IdAndStatusInOrderByIssueSequenceDesc(eventId, ACTIVE_STATUSES);

		int excessCount = activeIssues.size() - couponEvent.getTotalStock();
		if (excessCount <= 0) {
			return RecoveryOutcome.success(Scope.ofEvent(eventId), Map.of("eventId", eventId, "excessCount", 0),
					String.format("이벤트 %d는 초과발급 상태가 아닙니다.", eventId));
		}

		List<CouponIssue> excessIssues = activeIssues.subList(0, excessCount);
		LocalDateTime now = LocalDateTime.now();

		List<Long> revokedIssueIds = new ArrayList<>();
		List<Long> unrevokableIssueIds = new ArrayList<>();

		for (CouponIssue excessIssue : excessIssues) {
			CouponIssue lockedIssue = couponIssueRepository.findByIdForUpdate(excessIssue.getId())
					.orElseThrow(() -> new IllegalStateException("존재하지 않는 발급 건입니다. issueId=" + excessIssue.getId()));

			if (lockedIssue.getStatus() != CouponIssueStatus.ISSUED) {
				unrevokableIssueIds.add(lockedIssue.getId());
				continue;
			}

			CouponIssueStatus previousStatus = lockedIssue.getStatus();
			lockedIssue.revoke(now);

			couponHistoryRepository.save(CouponHistory.builder()
					.couponIssue(lockedIssue)
					.fromStatus(previousStatus)
					.toStatus(CouponIssueStatus.CANCELED)
					.actor(REVOKE_ACTOR)
					.reason(REVOKE_REASON)
					.occurredAt(now)
					.recordedAt(now)
					.eventUid(UUID.randomUUID().toString())
					.build());

			revokedIssueIds.add(lockedIssue.getId());
		}

		if (!revokedIssueIds.isEmpty()) {
			couponEvent.releaseSlots(revokedIssueIds.size(), now);
		}

		Map<String, Object> detail = Map.of(
				"eventId", eventId,
				"excessCount", excessCount,
				"revokedIssueIds", revokedIssueIds,
				"unrevokableIssueIds", unrevokableIssueIds);

		if (!unrevokableIssueIds.isEmpty()) {
			return RecoveryOutcome.failure(Scope.ofEvent(eventId), detail,
					String.format("이벤트 %d의 초과발급 %d건 중 %d건만 회수했습니다. %d건은 이미 USED/EXPIRED 상태라 회수하지 못했으므로 "
									+ "관리자 확인이 필요합니다.",
							eventId, excessCount, revokedIssueIds.size(), unrevokableIssueIds.size()));
		}

		return RecoveryOutcome.success(Scope.ofEvent(eventId), detail,
				String.format("이벤트 %d의 초과발급 %d건을 모두 회수했습니다.", eventId, excessCount));
	}
}
