package com.ace.notify.sender;

import com.ace.notify.NotificationMessage;
import com.ace.notify.NotificationType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Component;

import java.util.Map;

// 실제 알림 전송 구현체. 로컬 SSE 커넥션에 바로 쏘지 않고 Redis Pub/Sub 채널에 발행한다.
// 여러 앱 인스턴스로 운영되기 때문에, 이벤트를 발행한 인스턴스와 관리자의 SSE 커넥션이
// 붙어있는 인스턴스가 다를 수 있어서다. 각 인스턴스가 이 채널을 구독해서 자신이 들고
// 있는 로컬 커넥션에만 전달한다 (com.ace.notify.sse 참고).
@Slf4j
@Component
@RequiredArgsConstructor
public class SseNotificationSender implements NotificationSender {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private final StringRedisTemplate redisTemplate;
	private final ChannelTopic notificationTopic;

	@Override
	public void send(NotificationType type, Long userId, Map<String, Object> payload) {
		try {
			String message = OBJECT_MAPPER.writeValueAsString(new NotificationMessage(type, userId, payload));
			redisTemplate.convertAndSend(notificationTopic.getTopic(), message);
		} catch (JsonProcessingException e) {
			log.error("알림 메시지 직렬화 실패. type={}, userId={}", type, userId, e);
		}
	}
}
