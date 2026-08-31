package com.ace.notify.listener;

import com.ace.event.consistency.ConsistencyBatchStartedEvent;
import com.ace.notify.NotificationType;
import com.ace.notify.sender.NotificationSender;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class ConsistencyBatchStartedNotifyListener {

	private final NotificationSender notificationSender;

	@EventListener
	public void handle(ConsistencyBatchStartedEvent event) {
		notificationSender.send(
				NotificationType.CONSISTENCY_BATCH_STARTED,
				null,   // 관리자 대상 알림이라 특정 유저 없음
				Map.of(
						"jobExecutionId", event.getJobExecutionId(),
					"totalSteps", event.getTotalSteps(),
					"completedChecks", event.getCompletedChecks(),
						"triggerType", event.getTriggerType()
				)
		);
	}
}
