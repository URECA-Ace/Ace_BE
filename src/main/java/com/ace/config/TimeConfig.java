package com.ace.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ApplicationTimeProperties.class)
public class TimeConfig {

	@Bean
	public Clock applicationClock(ApplicationTimeProperties properties) {
		return Clock.system(properties.zoneId());
	}
}
