package com.ace.consistency.recovery.policy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.ace.consistency.common.Scope;
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
 * StockConsistencyRecoveryPolicy가 이벤트 하나에 대해 실제로 재고를 복구하는 단위 작업.
 *
 * ALL 스코프 target은 여러 이벤트를 한 번에 복구하는데, 이 클래스의 recoverEvent()를
 * REQUIRES_NEW로 별도 물리 트랜잭션에서 실행해 이벤트마다 독립된 커밋/롤백 경계를 만든다.
 * 그래야 이벤트 A가 REQUIRES_NEW 없이 정책(Policy)과 같은 트랜잭션에 있었다면, 이벤트 B에서
 * catch 블록이 setRollbackOnly()를 호출했을 때 A/C의 변경까지 함께 롤백될 수 있다.
 * 이벤트마다 물리 트랜잭션을 분리하면 이벤트 B의 setRollbackOnly()가 이벤트 B 자신의
 * 트랜잭션만 롤백시키므로 A/C의 커밋과 무관해진다. RecoveryResult 이력은
 * RecoveryResultRecorder의 별도 REQUIRES_NEW 트랜잭션에서 저장된다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StockEventRecoveryExecutor {

	private static final List<CouponIssueStatus> ACTIVE_STATUSES =
			List.of(CouponIssueStatus.ISSUED, CouponIssueStatus.USED, CouponIssueStatus.EXPIRED);

	private static final String REVOKE_ACTOR = "SYSTEM_STOCK_RECOVERY";
	private static final String REVOKE_REASON = "STOCK_OVER_ISSUANCE_AUTO_REVOKE";

	private final CouponEventRepository couponEventRepository;
	private final CouponIssueRepository couponIssueRepository;
	private final CouponHistoryRepository couponHistoryRepository;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public RecoveryOutcome recoverEvent(Long eventId, RecoveryAction action) {
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
	 * 회수 후에는 실제 활성 발급 건수를 기준으로 issued_quantity/remaining_stock을 함께
	 * 재계산해, 이 액션 한 번만으로 STOCK_RECONCILE_COUNTER를 별도로 호출하지 않아도
	 * remaining_stock까지 정합성이 맞춰지도록 한다.
	 */
	private RecoveryOutcome revokeExcessIssuance(Long eventId) {
		CouponEvent couponEvent = couponEventRepository.findByIdForUpdate(eventId)
				.orElseThrow(() -> new IllegalStateException("존재하지 않는 이벤트입니다. eventId=" + eventId));

		List<CouponIssue> activeIssues = couponIssueRepository
				.findByCouponEvent_IdAndStatusInOrderByIssueSequenceDesc(eventId, ACTIVE_STATUSES);

		int excessCount = activeIssues.size() - couponEvent.getTotalStock();
		if (excessCount <= 0) {
			return RecoveryOutcome.alreadyResolved(Scope.ofEvent(eventId), Map.of("eventId", eventId, "excessCount", 0),
					String.format("이벤트 %d는 이미 초과발급이 해소된 상태입니다. 이전 시도에서 이미 처리됐을 수 있습니다(이력 저장 유실 가능성).", eventId));
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
			int actualActiveCountAfterRevoke = activeIssues.size() - revokedIssueIds.size();
			couponEvent.reconcileStock(actualActiveCountAfterRevoke, now);
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
