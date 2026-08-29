package com.ace.notify.sse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class NotifySseConfig {

	@Bean
	public ChannelTopic notificationTopic() {
		return new ChannelTopic("ace:notify");
	}

	@Bean
	public RedisMessageListenerContainer redisMessageListenerContainer(
			RedisConnectionFactory connectionFactory,
			NotificationRedisSubscriber subscriber,
			ChannelTopic notificationTopic) {
		RedisMessageListenerContainer container = new RedisMessageListenerContainer();
		container.setConnectionFactory(connectionFactory);
		container.addMessageListener(subscriber, notificationTopic);
		return container;
	}
}
