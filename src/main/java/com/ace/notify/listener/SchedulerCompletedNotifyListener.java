package com.ace.notify.listener;

import com.ace.event.scheduler.SchedulerCompletedEvent;
import com.ace.notify.NotificationType;
import com.ace.notify.sender.NotificationSender;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class SchedulerCompletedNotifyListener {

	private final NotificationSender notificationSender;

	@EventListener
	public void handle(SchedulerCompletedEvent event) {
		Map<String, Object> payload = new HashMap<>(event.getResult());
		payload.put("schedulerName", event.getSchedulerName());

		notificationSender.send(
				NotificationType.SCHEDULER_COMPLETED,
				null,   // 관리자 대상 알림이라 특정 유저 없음
				payload
		);
	}
}
