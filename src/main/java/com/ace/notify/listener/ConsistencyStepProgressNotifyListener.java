package com.ace.notify.listener;

import java.util.Map;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.ace.event.consistency.ConsistencyStepProgressEvent;
import com.ace.notify.NotificationType;
import com.ace.notify.sender.NotificationSender;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ConsistencyStepProgressNotifyListener {

	private final NotificationSender notificationSender;

	@EventListener
	public void handle(ConsistencyStepProgressEvent event) {
		notificationSender.send(
				NotificationType.CONSISTENCY_STEP_PROGRESS,
				null,
				Map.of(
						"jobExecutionId", event.getJobExecutionId(),
						"checkName", event.getCheckName(),
						"checkLabel", event.getCheckLabel(),
						"stepIndex", event.getStepIndex(),
						"totalSteps", event.getTotalSteps(),
						"eventIds", event.getEventIds(),
						"processedEventCount", event.getProcessedEventCount(),
						"totalEventCount", event.getTotalEventCount(),
						"violationCount", event.getViolationCount()));
	}
}
