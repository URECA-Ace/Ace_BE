package com.ace.notify.sse;

import com.ace.notify.NotificationMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

// Redis 채널을 구독해서, 어느 인스턴스에서 발행됐든 이 인스턴스가 들고 있는
// 로컬 SSE 커넥션(SseEmitterRegistry)에 전달한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationRedisSubscriber implements MessageListener {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private final SseEmitterRegistry emitterRegistry;

	@Override
	public void onMessage(Message message, byte[] pattern) {
		try {
			NotificationMessage notification =
					OBJECT_MAPPER.readValue(message.getBody(), NotificationMessage.class);
			emitterRegistry.broadcast(notification);
		} catch (IOException e) {
			log.error("알림 메시지 역직렬화 실패", e);
		}
	}
}
