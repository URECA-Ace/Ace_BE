package com.ace.notify;

import java.util.Map;

// Redis Pub/Sub 채널로 주고받는 알림 메시지. 발행 측(SseNotificationSender)과
// 구독 측(NotificationRedisSubscriber)이 공유하는 직렬화 계약이다.
public record NotificationMessage(NotificationType type, Long userId, Map<String, Object> payload) {
}
