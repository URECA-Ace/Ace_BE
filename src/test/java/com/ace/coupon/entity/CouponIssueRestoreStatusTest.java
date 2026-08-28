package com.ace.coupon.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ace.coupon.enums.CouponIssueStatus;

/**
 * restoreStatus()는 StateMachineConsistencyRecoveryPolicy 전용으로, 이 엔티티에서 유일하게
 * validateTransition() 상태머신 검증을 완전히 우회하는 public 메서드다. 이 테스트는 그 "검증
 * 없이 강제로 바꾼다"는 위험한 동작 자체를 명시적으로 고정해서, 나중에 누군가 이 메서드에
 * 실수로 검증을 추가하거나 반대로 다른 곳에서 함부로 갖다 써도 바로 드러나게 한다.
 */
class CouponIssueRestoreStatusTest {

	@Test
	@DisplayName("일반 전이 규칙으로는 EXPIRED에서 다른 상태로 갈 수 없다")
	void expiredCannotTransitionNormally() {
		assertThat(CouponIssueStatus.EXPIRED.allowedTransitions()).isEmpty();
		assertThat(CouponIssueStatus.EXPIRED.canTransitTo(CouponIssueStatus.ISSUED)).isFalse();
	}

	@Test
	@DisplayName("restoreStatus는 상태머신 규칙과 무관하게 대상 상태로 강제 전이한다 (EXPIRED -> ISSUED)")
	void restoreStatusBypassesStateMachine() {
		CouponIssue issue = CouponIssue.builder()
				.status(CouponIssueStatus.EXPIRED)
				.build();

		issue.restoreStatus(CouponIssueStatus.ISSUED);

		assertThat(issue.getStatus()).isEqualTo(CouponIssueStatus.ISSUED);
	}

	@Test
	@DisplayName("같은 전이를 일반 상태머신 메서드(use)로 시도하면 거부된다는 대조군 — restoreStatus만 예외적으로 허용된다")
	void normalTransitionMethodsStillEnforceRules() {
		CouponIssue issue = CouponIssue.builder()
				.status(CouponIssueStatus.EXPIRED)
				.build();

		assertThatThrownBy(() -> issue.use(java.time.LocalDateTime.now()))
				.isInstanceOf(IllegalStateException.class);
	}
}
