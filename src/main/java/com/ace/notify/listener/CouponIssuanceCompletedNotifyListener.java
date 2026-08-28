package com.ace.notify.listener;

import com.ace.event.coupon.CouponIssuanceCompletedEvent;
import com.ace.notify.NotificationType;
import com.ace.notify.sender.NotificationSender;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class CouponIssuanceCompletedNotifyListener {

	private final NotificationSender notificationSender;

	@EventListener
	public void handle(CouponIssuanceCompletedEvent event) {
		notificationSender.send(
				NotificationType.COUPON_ISSUANCE_ALL_COMPLETED,
				null,   // 관리자 대상 알림이라 특정 유저 없음
				Map.of(
						"eventId", event.getCouponEventId()
				)
		);
	}
}
