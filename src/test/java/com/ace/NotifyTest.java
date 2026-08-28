package com.ace;

import com.ace.event.consistency.ConsistencyBatchCompletedEvent;
import com.ace.event.consistency.ConsistencyCheckFailedEvent;
import com.ace.event.consistency.ConsistencyStepCompletedEvent;
import com.ace.event.coupon.CouponIssuanceCompletedEvent;
import com.ace.event.coupon.CouponIssueFailedBatchEvent;
import com.ace.event.coupon.CouponIssueFailedEvent;
import com.ace.event.coupon.CouponIssuedEvent;
import com.ace.event.scheduler.SchedulerCompletedEvent;
import com.ace.event.scheduler.SchedulerStartedEvent;
import com.ace.notify.NotificationType;
import com.ace.notify.listener.ConsistencyBatchCompletedNotifyListener;
import com.ace.notify.listener.ConsistencyFailureNotifyListener;
import com.ace.notify.listener.ConsistencyStepCompletedNotifyListener;
import com.ace.notify.listener.CouponIssuanceCompletedNotifyListener;
import com.ace.notify.listener.CouponIssueFailedBatchNotifyListener;
import com.ace.notify.listener.IssueFailureNotifyListener;
import com.ace.notify.listener.IssueSuccessNotifyListener;
import com.ace.notify.listener.SchedulerCompletedNotifyListener;
import com.ace.notify.listener.SchedulerStartedNotifyListener;
import com.ace.notify.sender.MockNotificationSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * 알림 기능(notify 도메인) 전체를 한 파일에서 검증하는 단위 테스트.
 * Mock 처리는 알림의 "발단"이 되는 쪽(쿠폰 발급 성공/실패, 정합성 검증 실패)에만 적용한다
 * — event 도메인의 이벤트 객체를 직접 만들고, DB 저장(VerificationResultRepository)을 대체한다.
 * 반면 notify 도메인 자체(리스너 + NotificationSender)는 실제 구현체(MockNotificationSender)를
 * 그대로 사용해서, 리스너 → 실제 알림 발송 로직(log.info)까지 실제로 동작하는지 검증한다.
 * Mockito @Spy로 감싸 실제 로직은 그대로 실행하면서 verify()로 호출 여부/인자도 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class NotifyTest {

	@Spy
	private MockNotificationSender notificationSender = new MockNotificationSender();

	@Test
	void 발급_성공_이벤트를_받으면_ISSUE_SUCCESS_알림을_발송한다() {
		IssueSuccessNotifyListener listener = new IssueSuccessNotifyListener(notificationSender);

		CouponIssuedEvent event = CouponIssuedEvent.builder()
				.userId(1L)
				.couponEventId(100L)
				.issuedAt(LocalDateTime.now())
				.build();

		listener.handle(event);

		verify(notificationSender).send(
				NotificationType.ISSUE_SUCCESS,
				1L,
				Map.of("eventId", 100L)
		);
	}

	@Test
	void 발급_실패_이벤트를_받으면_ISSUE_FAILED_알림을_발송한다() {
		IssueFailureNotifyListener listener = new IssueFailureNotifyListener(notificationSender);

		CouponIssueFailedEvent event = CouponIssueFailedEvent.builder()
				.userId(1L)
				.couponEventId(100L)
				.reason(CouponIssueFailedEvent.FailReason.SOLD_OUT)
				.failedAt(LocalDateTime.now())
				.build();

		listener.handle(event);

		verify(notificationSender).send(
				NotificationType.ISSUE_FAILED,
				1L,
				Map.of("eventId", 100L, "reason", "SOLD_OUT")
		);
	}

	@Test
	void 발급_실패_집계_이벤트를_받으면_userId_없이_ISSUE_FAILED_BATCH_알림을_발송한다() {
		CouponIssueFailedBatchNotifyListener listener = new CouponIssueFailedBatchNotifyListener(notificationSender);

		CouponIssueFailedBatchEvent event = CouponIssueFailedBatchEvent.builder()
				.couponEventId(100L)
				.reason(CouponIssueFailedEvent.FailReason.SOLD_OUT)
				.count(9842L)
				.windowEndedAt(LocalDateTime.now())
				.build();

		listener.handle(event);

		verify(notificationSender).send(
				NotificationType.ISSUE_FAILED_BATCH,
				null,
				Map.of("eventId", 100L, "reason", "SOLD_OUT", "count", 9842L)
		);
	}

	@Test
	void 정합성_검증_실패_이벤트를_받으면_userId_없이_CONSISTENCY_CHECK_FAILED_알림을_발송한다() {
		ConsistencyFailureNotifyListener listener = new ConsistencyFailureNotifyListener(notificationSender);

		ConsistencyCheckFailedEvent event = ConsistencyCheckFailedEvent.builder()
				.checkName("StockConsistencyCheck")
				.triggerType("EVENT_TRIGGER")
				.scopeDescription("Scope[EVENT, eventId=100]")
				.violationCount(3L)
				.diffDetail(Map.of("expected", 10000, "actual", 10003))
				.detectedAt(LocalDateTime.now())
				.build();

		listener.handle(event);

		ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
		verify(notificationSender).send(
				ArgumentMatchers.eq(NotificationType.CONSISTENCY_CHECK_FAILED),
				ArgumentMatchers.isNull(),
				payloadCaptor.capture()
		);

		Map<String, Object> payload = payloadCaptor.getValue();
		assertThat(payload)
				.containsEntry("checkName", "StockConsistencyCheck")
				.containsEntry("triggerType", "EVENT_TRIGGER")
				.containsEntry("scopeDescription", "Scope[EVENT, eventId=100]")
				.containsEntry("violationCount", 3L)
				.containsEntry("diffDetail", Map.of("expected", 10000, "actual", 10003));
	}

	@Test
	void 회차_전체_발급_완료_이벤트를_받으면_userId_없이_COUPON_ISSUANCE_ALL_COMPLETED_알림을_발송한다() {
		CouponIssuanceCompletedNotifyListener listener = new CouponIssuanceCompletedNotifyListener(notificationSender);

		CouponIssuanceCompletedEvent event = CouponIssuanceCompletedEvent.builder()
				.couponEventId(100L)
				.completedAt(LocalDateTime.now())
				.build();

		listener.handle(event);

		verify(notificationSender).send(
				NotificationType.COUPON_ISSUANCE_ALL_COMPLETED,
				null,
				Map.of("eventId", 100L)
		);
	}

	@Test
	void ALL_배치_Step_완료_이벤트를_받으면_userId_없이_CONSISTENCY_STEP_COMPLETED_알림을_발송한다() {
		ConsistencyStepCompletedNotifyListener listener = new ConsistencyStepCompletedNotifyListener(notificationSender);

		ConsistencyStepCompletedEvent event = ConsistencyStepCompletedEvent.builder()
				.checkName("StockConsistencyCheck")
				.triggerType("SCHEDULED")
				.status("FAIL")
				.violationCount(3)
				.completedAt(LocalDateTime.now())
				.build();

		listener.handle(event);

		verify(notificationSender).send(
				NotificationType.CONSISTENCY_STEP_COMPLETED,
				null,
				Map.of(
						"checkName", "StockConsistencyCheck",
						"triggerType", "SCHEDULED",
						"status", "FAIL",
						"violationCount", 3
				)
		);
	}

	@Test
	void ALL_배치_전체_완료_이벤트를_받으면_userId_없이_CONSISTENCY_BATCH_COMPLETED_알림을_발송한다() {
		ConsistencyBatchCompletedNotifyListener listener = new ConsistencyBatchCompletedNotifyListener(notificationSender);

		ConsistencyBatchCompletedEvent event = ConsistencyBatchCompletedEvent.builder()
				.jobExecutionId(500L)
				.status("COMPLETED")
				.stepCount(5)
				.completedAt(LocalDateTime.now())
				.build();

		listener.handle(event);

		verify(notificationSender).send(
				NotificationType.CONSISTENCY_BATCH_COMPLETED,
				null,
				Map.of(
						"jobExecutionId", 500L,
						"status", "COMPLETED",
						"stepCount", 5
				)
		);
	}

	@Test
	void 스케쥴러_시작_이벤트를_받으면_userId_없이_SCHEDULER_STARTED_알림을_발송한다() {
		SchedulerStartedNotifyListener listener = new SchedulerStartedNotifyListener(notificationSender);

		SchedulerStartedEvent event = SchedulerStartedEvent.builder()
				.schedulerName("COUPON_EXPIRATION")
				.startedAt(LocalDateTime.now())
				.build();

		listener.handle(event);

		verify(notificationSender).send(
				NotificationType.SCHEDULER_STARTED,
				null,
				Map.of("schedulerName", "COUPON_EXPIRATION")
		);
	}

	@Test
	void 스케쥴러_완료_이벤트를_받으면_userId_없이_결과와_함께_SCHEDULER_COMPLETED_알림을_발송한다() {
		SchedulerCompletedNotifyListener listener = new SchedulerCompletedNotifyListener(notificationSender);

		SchedulerCompletedEvent event = SchedulerCompletedEvent.builder()
				.schedulerName("COUPON_EXPIRATION")
				.result(Map.of("expiredCount", 12))
				.completedAt(LocalDateTime.now())
				.build();

		listener.handle(event);

		ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
		verify(notificationSender).send(
				ArgumentMatchers.eq(NotificationType.SCHEDULER_COMPLETED),
				ArgumentMatchers.isNull(),
				payloadCaptor.capture()
		);

		Map<String, Object> payload = payloadCaptor.getValue();
		assertThat(payload)
				.containsEntry("schedulerName", "COUPON_EXPIRATION")
				.containsEntry("expiredCount", 12);
	}

}
