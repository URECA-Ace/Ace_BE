package com.ace.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
public class RedisConfig {

	@Bean
	public RedisScript<Long> issueCouponScript(){
		DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
		redisScript.setLocation( new ClassPathResource("scripts/issueCoupon.lua"));
		
		redisScript.setResultType(Long.class);
		
		return redisScript;
	}
	
	
	
}
