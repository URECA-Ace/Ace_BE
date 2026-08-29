package com.ace.notify.sender;

import com.ace.notify.NotificationType;

import java.util.Map;

public interface NotificationSender {
	void send(NotificationType type, Long userId, Map<String, Object> payload);
}