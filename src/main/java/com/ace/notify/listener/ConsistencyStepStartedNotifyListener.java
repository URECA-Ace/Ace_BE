package com.ace.notify.listener;

import com.ace.event.consistency.ConsistencyStepStartedEvent;
import com.ace.notify.NotificationType;
import com.ace.notify.sender.NotificationSender;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class ConsistencyStepStartedNotifyListener {

	private final NotificationSender notificationSender;

	@EventListener
	public void handle(ConsistencyStepStartedEvent event) {
		notificationSender.send(
				NotificationType.CONSISTENCY_STEP_STARTED,
				null,   // 관리자 대상 알림이라 특정 유저 없음
				Map.of(
						"checkName", event.getCheckName(),
						"checkLabel", event.getCheckLabel() == null ? event.getCheckName() : event.getCheckLabel(),
						"triggerType", event.getTriggerType()
				)
		);
	}
}
