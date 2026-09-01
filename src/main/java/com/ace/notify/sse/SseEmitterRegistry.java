package com.ace.notify.sse;

import com.ace.notify.NotificationMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

// 이 인스턴스에 붙어있는 SSE 커넥션 목록. 지금은 인증 없이 전체 브로드캐스트로만
// 동작한다 (userId별 타겟팅 없이 관리자 콘솔에 붙은 모든 커넥션에 동일하게 전달).
@Slf4j
@Component
public class SseEmitterRegistry {

	private static final long NO_TIMEOUT = Long.MAX_VALUE;

	private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

	public SseEmitter register() {
		SseEmitter emitter = new SseEmitter(NO_TIMEOUT);
		emitters.add(emitter);
		emitter.onCompletion(() -> emitters.remove(emitter));
		emitter.onTimeout(() -> emitters.remove(emitter));
		emitter.onError(e -> emitters.remove(emitter));
		return emitter;
	}

	public void broadcast(NotificationMessage message) {
		for (SseEmitter emitter : emitters) {
			try {
				emitter.send(SseEmitter.event().data(message));
			} catch (IOException e) {
				emitters.remove(emitter);
			}
		}
	}

	// 프록시/브라우저가 유휴 커넥션을 끊지 않도록 주기적으로 하트비트(주석 라인)를 보낸다.
	@Scheduled(fixedDelay = 20000)
	public void heartbeat() {
		for (SseEmitter emitter : emitters) {
			try {
				emitter.send(SseEmitter.event().comment("heartbeat"));
			} catch (IOException e) {
				emitters.remove(emitter);
			}
		}
	}
}
