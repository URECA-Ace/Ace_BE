package com.ace.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.ace.coupon.persistence.CouponIssuePersistenceProperties;

//저장 계층 설정 등록
// SYNC 모드는 Redis 소비와 무관
@Configuration
@EnableConfigurationProperties(CouponIssuePersistenceProperties.class)
public class PersistenceConfig {
}
