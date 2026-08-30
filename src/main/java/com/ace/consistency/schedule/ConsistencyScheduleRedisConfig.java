package com.ace.consistency.schedule;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class ConsistencyScheduleRedisConfig {

	@Bean
	public ChannelTopic consistencyScheduleChangedTopic() {
		return new ChannelTopic("ace:consistency:schedule:changed");
	}

	@Bean
	public RedisMessageListenerContainer consistencyScheduleRedisMessageListenerContainer(
			RedisConnectionFactory connectionFactory,
			ConsistencyScheduleRedisSubscriber subscriber,
			@Qualifier("consistencyScheduleChangedTopic") ChannelTopic consistencyScheduleChangedTopic) {
		RedisMessageListenerContainer container = new RedisMessageListenerContainer();
		container.setConnectionFactory(connectionFactory);
		container.addMessageListener(subscriber, consistencyScheduleChangedTopic);
		return container;
	}
}
