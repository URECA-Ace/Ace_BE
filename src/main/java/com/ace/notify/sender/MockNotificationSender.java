package com.ace.notify.sender;

import com.ace.notify.NotificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class MockNotificationSender implements NotificationSender {

	@Override
	public void send(NotificationType type, Long userId, Map<String, Object> payload) {
		log.info("[MOCK 알림 발송] type={}, userId={}, payload={}", type, userId, payload);
	}
}