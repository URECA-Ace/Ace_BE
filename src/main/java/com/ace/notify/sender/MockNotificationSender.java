package com.ace.notify.sender;

import com.ace.notify.NotificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

// 실제 전송은 SseNotificationSender가 담당한다. 이 클래스는 테스트에서 직접
// new로 생성해서 쓰는 용도로만 남겨두고, 컴포넌트 스캔 대상에서는 뺀다.
@Slf4j
@RequiredArgsConstructor
public class MockNotificationSender implements NotificationSender {

	@Override
	public void send(NotificationType type, Long userId, Map<String, Object> payload) {
		log.info("[MOCK 알림 발송] type={}, userId={}, payload={}", type, userId, payload);
	}
}