package com.ace.consistency.recovery.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ace.consistency.recovery.RecoveryOutcome;
import com.ace.consistency.recovery.enums.RecoveryAction;
import com.ace.consistency.recovery.enums.RecoveryResultStatus;
import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.entity.CouponIssue;
import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.enums.CouponIssueStatus;
import com.ace.coupon.repository.CouponEventRepository;
import com.ace.coupon.repository.CouponHistoryRepository;
import com.ace.coupon.repository.CouponIssueRepository;

@ExtendWith(MockitoExtension.class)
class StockEventRecoveryExecutorTest {

	private static final Long EVENT_ID = 1L;

	@Mock
	private CouponEventRepository couponEventRepository;

	@Mock
	private CouponIssueRepository couponIssueRepository;

	@Mock
	private CouponHistoryRepository couponHistoryRepository;

	private StockEventRecoveryExecutor executor;

	@BeforeEach
	void setUp() {
		executor = new StockEventRecoveryExecutor(couponEventRepository, couponIssueRepository, couponHistoryRepository);
	}

	private CouponEvent couponEventWithCounters(int totalStock, int issuedQuantity, int remainingStock) {
		return CouponEvent.builder()
				.id(EVENT_ID)
				.round(1)
				.openAt(LocalDateTime.now().minusDays(1))
				.closeAt(LocalDateTime.now().plusDays(1))
				.totalStock(totalStock)
				.issuedQuantity(issuedQuantity)
				.remainingStock(remainingStock)
				.perUserLimit(1)
				.status(CouponEventStatus.OPEN)
				.createdAt(LocalDateTime.now())
				.updatedAt(LocalDateTime.now())
				.build();
	}

	private CouponIssue issueWithStatus(Long id, CouponIssueStatus status) {
		return CouponIssue.builder()
				.id(id)
				.status(status)
				.build();
	}

	@Test
	void 실제_활성_발급_건수에_맞춰_재고_카운터를_재계산하고_SUCCESS를_반환한다() {
		CouponEvent couponEvent = couponEventWithCounters(100, 80, 10); // 80 + 10 != 100 (10건 어긋남)
		given(couponEventRepository.findByIdForUpdate(EVENT_ID)).willReturn(Optional.of(couponEvent));
		given(couponIssueRepository.countByCouponEvent_IdAndStatusIn(eq(EVENT_ID), anyList())).willReturn(70L);

		RecoveryOutcome outcome = executor.recoverEvent(EVENT_ID, RecoveryAction.STOCK_RECONCILE_COUNTER);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.SUCCESS);
		assertThat(couponEvent.getIssuedQuantity()).isEqualTo(70);
		assertThat(couponEvent.getRemainingStock()).isEqualTo(30); // totalStock(100) - actualActiveCount(70)
		assertThat(outcome.getDetail())
				.containsEntry("beforeIssuedQuantity", 80)
				.containsEntry("afterIssuedQuantity", 70);
		assertThat(outcome.getRevalidationScope().getEventId()).isEqualTo(EVENT_ID);
	}

	@Test
	void 실제_활성_발급_건수가_총_재고를_초과하면_카운터_재계산을_거부한다() {
		CouponEvent couponEvent = couponEventWithCounters(100, 100, 0);
		given(couponEventRepository.findByIdForUpdate(EVENT_ID)).willReturn(Optional.of(couponEvent));
		given(couponIssueRepository.countByCouponEvent_IdAndStatusIn(eq(EVENT_ID), anyList())).willReturn(105L);

		RecoveryOutcome outcome = executor.recoverEvent(EVENT_ID, RecoveryAction.STOCK_RECONCILE_COUNTER);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.FAIL);
		assertThat(outcome.getMessage()).contains("STOCK_REVOKE_EXCESS_ISSUANCE");
		assertThat(couponEvent.getIssuedQuantity()).isEqualTo(100); // 건드리지 않음
	}

	@Test
	void 이벤트가_존재하지_않으면_예외를_던지지_않고_FAIL_Outcome을_반환한다() {
		given(couponEventRepository.findByIdForUpdate(EVENT_ID)).willReturn(Optional.empty());

		RecoveryOutcome outcome = executor.recoverEvent(EVENT_ID, RecoveryAction.STOCK_RECONCILE_COUNTER);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.FAIL);
		assertThat(outcome.getMessage()).contains("eventId=" + EVENT_ID);
	}

	@Test
	void 지원하지_않는_액션이면_FAIL_Outcome을_반환한다() {
		RecoveryOutcome outcome = executor.recoverEvent(EVENT_ID, RecoveryAction.DEFAULT);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.FAIL);
		assertThat(outcome.getMessage()).contains("STOCK_RECONCILE_COUNTER").contains("STOCK_REVOKE_EXCESS_ISSUANCE");
	}

	@Test
	void 초과발급이_아니면_아무것도_회수하지_않고_SUCCESS를_반환한다() {
		CouponEvent couponEvent = couponEventWithCounters(10, 1, 9);
		given(couponEventRepository.findByIdForUpdate(EVENT_ID)).willReturn(Optional.of(couponEvent));
		given(couponIssueRepository.findByCouponEvent_IdAndStatusInOrderByIssueSequenceDesc(eq(EVENT_ID), anyList()))
				.willReturn(List.of(issueWithStatus(1L, CouponIssueStatus.ISSUED)));

		RecoveryOutcome outcome = executor.recoverEvent(EVENT_ID, RecoveryAction.STOCK_REVOKE_EXCESS_ISSUANCE);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.SUCCESS);
		assertThat(outcome.getDetail()).containsEntry("catchUp", true); // 저장 유실 후 재시도로 들어온 경우와 구분하기 위한 표식
		verify(couponIssueRepository, never()).findByIdForUpdate(any());
	}

	@Test
	void 초과분_중_ISSUED_상태만_최신순으로_회수하고_나머지는_회수_불가로_보고한다() {
		CouponEvent couponEvent = couponEventWithCounters(1, 3, -2);
		CouponIssue mostRecent = issueWithStatus(3L, CouponIssueStatus.ISSUED);
		CouponIssue usedIssue = issueWithStatus(2L, CouponIssueStatus.USED);
		CouponIssue oldest = issueWithStatus(1L, CouponIssueStatus.ISSUED);

		given(couponEventRepository.findByIdForUpdate(EVENT_ID)).willReturn(Optional.of(couponEvent));
		given(couponIssueRepository.findByCouponEvent_IdAndStatusInOrderByIssueSequenceDesc(eq(EVENT_ID), anyList()))
				.willReturn(List.of(mostRecent, usedIssue, oldest));
		given(couponIssueRepository.findByIdForUpdate(3L)).willReturn(Optional.of(mostRecent));
		given(couponIssueRepository.findByIdForUpdate(2L)).willReturn(Optional.of(usedIssue));

		RecoveryOutcome outcome = executor.recoverEvent(EVENT_ID, RecoveryAction.STOCK_REVOKE_EXCESS_ISSUANCE);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.FAIL); // 회수 불가 건이 남아 있으므로 관리자 확인 필요
		assertThat(outcome.getDetail()).containsEntry("excessCount", 2);
		assertThat(mostRecent.getStatus()).isEqualTo(CouponIssueStatus.CANCELED);
		assertThat(oldest.getStatus()).isEqualTo(CouponIssueStatus.ISSUED);
		verify(couponHistoryRepository, times(1)).save(any());
		assertThat(couponEvent.getIssuedQuantity()).isEqualTo(2);
		assertThat(couponEvent.getRemainingStock()).isEqualTo(-1); // totalStock(1) - 회수 후 실제 활성 건수(2)로 재계산됨
	}

	@Test
	void 초과분이_모두_ISSUED_상태이면_전부_회수하고_SUCCESS를_반환한다() {
		CouponEvent couponEvent = couponEventWithCounters(1, 2, -1);
		CouponIssue mostRecent = issueWithStatus(2L, CouponIssueStatus.ISSUED);
		CouponIssue oldest = issueWithStatus(1L, CouponIssueStatus.ISSUED);

		given(couponEventRepository.findByIdForUpdate(EVENT_ID)).willReturn(Optional.of(couponEvent));
		given(couponIssueRepository.findByCouponEvent_IdAndStatusInOrderByIssueSequenceDesc(eq(EVENT_ID), anyList()))
				.willReturn(List.of(mostRecent, oldest));
		given(couponIssueRepository.findByIdForUpdate(2L)).willReturn(Optional.of(mostRecent));

		RecoveryOutcome outcome = executor.recoverEvent(EVENT_ID, RecoveryAction.STOCK_REVOKE_EXCESS_ISSUANCE);

		assertThat(outcome.getStatus()).isEqualTo(RecoveryResultStatus.SUCCESS);
		assertThat(mostRecent.getStatus()).isEqualTo(CouponIssueStatus.CANCELED);
		assertThat(couponEvent.getIssuedQuantity()).isEqualTo(1);
		assertThat(couponEvent.getRemainingStock()).isEqualTo(0); // totalStock(1) - 회수 후 실제 활성 건수(1)로 재계산됨
	}
}
