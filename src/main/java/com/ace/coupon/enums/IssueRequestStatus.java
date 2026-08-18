package com.ace.coupon.enums;

public enum IssueRequestStatus {

    /**
     * Redis 원자적 판정을 통과하고 비동기 저장을 기다리는 상태
     */
	ACCEPTED(0),

    /**
     * Kafka Consumer가 MySQL 저장을 처리 중인 상태
     */
	PROCESSING(1),

    /**
     * MySQL에 쿠폰 발급이 확정된 상태
     */
	ISSUED(2),

    /**
     * 비동기 저장이 최종 실패한 상태
     */
	FAILED(3),

    /**
     * 최종 실패 후 Redis 재고와 중복 상태가 원복된 상태
     */
	COMPENSATED(4),

	/**
	 * 재고 소진 판정 상태
	 */
	REJECTED_SOLD_OUT(5),

	/**
	 * 사용자 중복 판정 상태
	 */
	REJECTED_DUPLICATE(6),

	/**
	 * 오픈 전 판정 상태
	 */
	REJECTED_NOT_OPEN(7),

	/**
	 * 마감 후 판정 상태
	 */
	REJECTED_CLOSED(8);

	private final long redisCode;

	IssueRequestStatus(long redisCode) {
		this.redisCode = redisCode;
	}

	public long redisCode() {
		return redisCode;
	}

	public static IssueRequestStatus fromRedisCode(long redisCode) {
		for (IssueRequestStatus status : values()) {
			if (status.redisCode == redisCode) {
				return status;
			}
		}
		throw new IllegalStateException("정의되지 않은 Redis 요청 상태 코드: " + redisCode);
	}
}
