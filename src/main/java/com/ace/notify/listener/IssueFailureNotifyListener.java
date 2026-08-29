package com.ace.notify.listener;

import com.ace.event.coupon.CouponIssueFailedEvent;
import com.ace.notify.NotificationType;
import com.ace.notify.sender.NotificationSender;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class IssueFailureNotifyListener {

	private final NotificationSender notificationSender;

	@Async
	@EventListener
	public void handle(CouponIssueFailedEvent event) {
		notificationSender.send(
				NotificationType.ISSUE_FAILED,
				event.getUserId(),
				Map.of(
						"eventId", event.getCouponEventId(),
						"reason", event.getReason().name()
				)
		);
	}
}