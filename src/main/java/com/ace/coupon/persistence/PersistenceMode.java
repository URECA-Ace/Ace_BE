package com.ace.coupon.persistence;

// 발급 이력 저장 경로
public enum PersistenceMode {

	// 요청 스레드에서 MySQL 저장까지 끝내고 응답(1차)
	SYNC,

	// 판정 직후 응답하고, Stream 소비자가 저장(2차)
	RELAY
}
