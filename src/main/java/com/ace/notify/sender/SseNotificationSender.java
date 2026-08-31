package com.ace.notify.sender;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.ace.notify.NotificationMessage;
import com.ace.notify.NotificationType;
import com.ace.notify.sse.SseEmitterRegistry;

import lombok.RequiredArgsConstructor;

/** 단일 인스턴스가 보유한 SSE 연결에 알림을 직접 전달한다. */
@Component
@RequiredArgsConstructor
public class SseNotificationSender implements NotificationSender {

	private final SseEmitterRegistry emitterRegistry;

	@Override
	public void send(NotificationType type, Long userId, Map<String, Object> payload) {
		emitterRegistry.broadcast(new NotificationMessage(type, userId, payload));
	}
}
