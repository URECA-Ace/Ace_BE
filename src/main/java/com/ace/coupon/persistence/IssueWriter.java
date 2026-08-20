package com.ace.coupon.persistence;

// 발급 이력 저장 구현
// 이후 2차 MVP를 고려하여 구현만 교체하기 쉽게 인터페이스 생성
public interface IssueWriter {

	// 발급과 상태 전이 이력 저장
	long write(IssueRecord record, CampaignMetadata metadata);
}
