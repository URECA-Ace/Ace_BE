package com.ace.config;

import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "application.time")
public record ApplicationTimeProperties(ZoneId zoneId) {

	private static final ZoneId DEFAULT_ZONE_ID = ZoneId.of("Asia/Seoul");

	public ApplicationTimeProperties {
		zoneId = zoneId == null ? DEFAULT_ZONE_ID : zoneId;
	}
}
