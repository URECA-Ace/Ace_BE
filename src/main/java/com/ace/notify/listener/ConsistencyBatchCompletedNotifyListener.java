package com.ace.notify.listener;

import com.ace.event.consistency.ConsistencyBatchCompletedEvent;
import com.ace.notify.NotificationType;
import com.ace.notify.sender.NotificationSender;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class ConsistencyBatchCompletedNotifyListener {

	private final NotificationSender notificationSender;

	// ConsistencyJobExecutionListener는 Spring 빈이 아니라 afterJob()이 실제 트랜잭션 안에서
	// 실행되지 않는다. 따라서 커밋을 전제로 하는 @TransactionalEventListener(AFTER_COMMIT)를
	// 쓰면 이벤트가 조용히 버려지므로, 일반 @EventListener를 사용해야 한다.
	@EventListener
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
