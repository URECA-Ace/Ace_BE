package com.ace.coupon.enums;

public enum IssueRequestStatus {

    /**
     * Redis 원자적 판정을 통과하고 비동기 저장을 기다리는 상태
     */
    ACCEPTED,

    /**
     * Kafka Consumer가 MySQL 저장을 처리 중인 상태
     */
    PROCESSING,

    /**
     * MySQL에 쿠폰 발급이 확정된 상태
     */
    ISSUED,

    /**
     * 비동기 저장이 최종 실패한 상태
     */
    FAILED,

    /**
     * 최종 실패 후 Redis 재고와 중복 상태가 원복된 상태
     */
    COMPENSATED
}