package com.ace.coupon.persistence;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

// 저장 트랜잭션 경계(발급과 이력을 한 트랜잭션에)
// 확정/보상은 여기서 X
@Service
@RequiredArgsConstructor
public class IssuePersistenceService {

	private final IssueWriter issueWriter;
	private final CampaignMetadataCache campaignMetadataCache;

	// 저장된 coupon_issue.issue_id 반환
	@Transactional
	public long persist(IssueRecord record) {
		if (record == null) {
			throw new IllegalArgumentException("저장 입력이 필요합니다.");
		}

		CampaignMetadata metadata = campaignMetadataCache.get(record.campaignId());
		return issueWriter.write(record, metadata);
	}
}
