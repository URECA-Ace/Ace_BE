package com.ace.notify.listener;

import com.ace.event.scheduler.SchedulerStartedEvent;
import com.ace.notify.NotificationType;
import com.ace.notify.sender.NotificationSender;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class SchedulerStartedNotifyListener {

	private final NotificationSender notificationSender;

	@EventListener
	public void handle(SchedulerStartedEvent event) {
		notificationSender.send(
				NotificationType.SCHEDULER_STARTED,
				null,   // 관리자 대상 알림이라 특정 유저 없음
				Map.of(
						"schedulerName", event.getSchedulerName()
				)
		);
	}
}
