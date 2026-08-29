package com.ace.consistency.schedule;

import java.nio.charset.StandardCharsets;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// Redis 채널을 구독해서, 어느 인스턴스에서 주기 변경 API가 호출됐든 이 인스턴스가 들고 있는
// 로컬 예약(ConsistencySchedulerCoordinator)에도 똑같이 반영한다. 메시지 형식: "{schedulerName}:{intervalMs}"
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsistencyScheduleRedisSubscriber implements MessageListener {

	private final ConsistencySchedulerCoordinator coordinator;

	@Override
	public void onMessage(Message message, byte[] pattern) {
		String body = new String(message.getBody(), StandardCharsets.UTF_8);
		int separatorIndex = body.lastIndexOf(':');
		if (separatorIndex < 0) {
			log.warn("잘못된 스케줄 변경 메시지 형식입니다: {}", body);
			return;
		}
		try {
			String schedulerName = body.substring(0, separatorIndex);
			long intervalMs = Long.parseLong(body.substring(separatorIndex + 1));
			coordinator.applyIntervalChange(schedulerName, intervalMs);
		} catch (NumberFormatException e) {
			log.warn("잘못된 스케줄 변경 메시지 형식입니다: {}", body);
		}
	}
}
