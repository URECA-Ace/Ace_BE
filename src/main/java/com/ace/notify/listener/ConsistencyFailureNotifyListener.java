package com.ace.notify.listener;

import com.ace.event.consistency.ConsistencyCheckFailedEvent;
import com.ace.notify.NotificationType;
import com.ace.notify.sender.NotificationSender;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ConsistencyFailureNotifyListener {

	private final NotificationSender notificationSender;

	@Async
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void handle(ConsistencyCheckFailedEvent event) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("checkName", event.getCheckName());
		payload.put("triggerType", event.getTriggerType());
		payload.put("scopeDescription", event.getScopeDescription());
		payload.put("violationCount", event.getViolationCount());
		payload.put("diffDetail", event.getDiffDetail());

		notificationSender.send(
				NotificationType.CONSISTENCY_CHECK_FAILED,
				null,   // 관리자 대상 알림이라 특정 유저 없음
				payload
		);
	}
}