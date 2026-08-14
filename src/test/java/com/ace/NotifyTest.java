package com.ace;

import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import com.ace.consistency.common.VerificationResult;
import com.ace.consistency.common.VerificationResultPersister;
import com.ace.consistency.entity.VerificationResultEntity;
import com.ace.consistency.repository.VerificationResultRepository;
import com.ace.event.consistency.ConsistencyCheckFailedEvent;
import com.ace.event.coupon.CouponIssueFailedEvent;
import com.ace.event.coupon.CouponIssuedEvent;
import com.ace.notify.NotificationType;
import com.ace.notify.listener.ConsistencyFailureNotifyListener;
import com.ace.notify.listener.IssueFailureNotifyListener;
import com.ace.notify.listener.IssueSuccessNotifyListener;
import com.ace.notify.sender.MockNotificationSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

	@Mock
	private VerificationResultRepository resultRepository;

	@Mock
	private ApplicationEventPublisher eventPublisher;

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
	void 결과가_모두_PASS면_저장은_하되_알림_이벤트는_발행하지_않는다() {
		VerificationResultPersister persister = new VerificationResultPersister(resultRepository, eventPublisher);

		Scope scope = Scope.ofEvent(100L);
		TriggerType triggerType = TriggerType.EVENT_TRIGGER;
		List<VerificationResult> results = List.of(
				VerificationResult.pass("StockConsistencyCheck", triggerType, scope, LocalDateTime.now(), 10L)
		);

		persister.saveAndNotify(results, scope, triggerType);

		verify(resultRepository).saveAll(anyList());
		verify(eventPublisher, never()).publishEvent(ArgumentMatchers.any());
	}

	@Test
	void FAIL_결과가_있으면_전체_결과를_저장하고_FAIL_건에_대해서만_알림_이벤트를_발행한다() {
		VerificationResultPersister persister = new VerificationResultPersister(resultRepository, eventPublisher);

		Scope scope = Scope.ofEvent(100L);
		TriggerType triggerType = TriggerType.EVENT_TRIGGER;
		Map<String, Object> diffDetail = Map.of("expected", 10000, "actual", 10003);

		VerificationResult passed = VerificationResult.pass(
				"DuplicateConsistencyCheck", triggerType, scope, LocalDateTime.now(), 5L);
		VerificationResult failed = VerificationResult.fail(
				"StockConsistencyCheck", triggerType, scope, 3, diffDetail, LocalDateTime.now(), 10L);
		List<VerificationResult> results = List.of(passed, failed);

		persister.saveAndNotify(results, scope, triggerType);

		ArgumentCaptor<List<VerificationResultEntity>> savedCaptor = ArgumentCaptor.forClass(List.class);
		verify(resultRepository).saveAll(savedCaptor.capture());
		assertThat(savedCaptor.getValue()).hasSize(2);

		ArgumentCaptor<ConsistencyCheckFailedEvent> eventCaptor =
				ArgumentCaptor.forClass(ConsistencyCheckFailedEvent.class);
		verify(eventPublisher, times(1)).publishEvent(eventCaptor.capture());

		ConsistencyCheckFailedEvent published = eventCaptor.getValue();
		assertThat(published.getCheckName()).isEqualTo("StockConsistencyCheck");
		assertThat(published.getTriggerType()).isEqualTo(triggerType.name());
		assertThat(published.getScopeDescription()).isEqualTo(scope.toString());
		assertThat(published.getViolationCount()).isEqualTo(3L);
		assertThat(published.getDiffDetail()).isEqualTo(diffDetail);
	}
}
