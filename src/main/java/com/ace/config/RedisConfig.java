package com.ace.config;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import com.ace.coupon.redis.CouponIssueRedisProperties;

@Configuration
@EnableConfigurationProperties(CouponIssueRedisProperties.class)
public class RedisConfig {

	@Bean
	public RedisScript<List> couponIssueScript() {
		return script("scripts/coupon-issue.lua", List.class);
	}

	@Bean
	public RedisScript<List> couponCampaignInitializeScript() {
		return script("scripts/coupon-campaign-initialize.lua", List.class);
	}

	@Bean
	public RedisScript<List> couponIssueCompensateScript() {
		return script("scripts/coupon-issue-compensate.lua", List.class);
	}

	@Bean
	public RedisScript<List> couponEventStatsScript() {
		return script("scripts/coupon-event-stats.lua", List.class);
	}

	private <T> RedisScript<T> script(String location, Class<T> resultType) {
		DefaultRedisScript<T> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource(location));
		script.setResultType(resultType);
		return script;
	}
}
