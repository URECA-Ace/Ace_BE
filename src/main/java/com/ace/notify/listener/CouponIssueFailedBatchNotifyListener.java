package com.ace.notify.listener;

import com.ace.event.coupon.CouponIssueFailedBatchEvent;
import com.ace.notify.NotificationType;
import com.ace.notify.sender.NotificationSender;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class CouponIssueFailedBatchNotifyListener {

	private final NotificationSender notificationSender;

	@EventListener
	public void handle(CouponIssueFailedBatchEvent event) {
		notificationSender.send(
				NotificationType.ISSUE_FAILED_BATCH,
				null,   // 다건 집계라 특정 유저 없음
				Map.of(
						"eventId", event.getCouponEventId(),
						"reason", event.getReason().name(),
						"count", event.getCount()
				)
		);
	}
}
