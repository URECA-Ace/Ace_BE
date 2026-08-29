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
		String message;
		try {
			message = OBJECT_MAPPER.writeValueAsString(new NotificationMessage(type, userId, payload));
		} catch (JsonProcessingException e) {
			log.error("알림 메시지 직렬화 실패. type={}, userId={}", type, userId, e);
			return;
		}

		try {
			redisTemplate.convertAndSend(notificationTopic.getTopic(), message);
		} catch (Exception e) {
			// 알림은 유실돼도 되는 부가 기능이다. Redis 장애로 convertAndSend()가 던지는 예외를
			// 여기서 격리하지 않으면, 동기 @EventListener를 쓰는 일부 호출부(스케줄러의 시작
			// 알림 발행, ConsistencyJobExecutionListener.beforeJob() 등)까지 예외가 전파되어
			// 만료 처리·정합성 검증 같은 핵심 로직 자체가 실행되지 못하는 문제가 생긴다.
			log.error("알림 메시지 전송 실패. type={}, userId={}", type, userId, e);
		}
	}
}
