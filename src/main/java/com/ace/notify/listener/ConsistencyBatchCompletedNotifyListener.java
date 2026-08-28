package com.ace.notify.listener;

import com.ace.event.consistency.ConsistencyBatchCompletedEvent;
import com.ace.notify.NotificationType;
import com.ace.notify.sender.NotificationSender;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class ConsistencyBatchCompletedNotifyListener {

	private final NotificationSender notificationSender;

	@Async
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void handle(ConsistencyBatchCompletedEvent event) {
		notificationSender.send(
				NotificationType.CONSISTENCY_BATCH_COMPLETED,
				null,   // 관리자 대상 알림이라 특정 유저 없음
				Map.of(
						"jobExecutionId", event.getJobExecutionId(),
						"status", event.getStatus(),
						"stepCount", event.getStepCount()
				)
		);
	}
}
