package com.ace.notify.listener;

import com.ace.event.coupon.CouponIssuedEvent;
import com.ace.notify.NotificationType;
import com.ace.notify.sender.NotificationSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class IssueSuccessNotifyListener {

	private final NotificationSender notificationSender;

	@Async
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void handle(CouponIssuedEvent event) {
		notificationSender.send(
				NotificationType.ISSUE_SUCCESS,
				event.getUserId(),
				Map.of(
						"eventId", event.getCouponEventId()
				)
		);
	}
}