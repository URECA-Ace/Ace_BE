package com.ace.coupon.persistence;

import java.time.LocalDateTime;

// 저장에 필요한 회차 불변값
public record CampaignMetadata(
		long eventId,
		int validHours,
		LocalDateTime openAt,
		LocalDateTime closeAt) {

	public CampaignMetadata {
		if (eventId <= 0) {
			throw new IllegalArgumentException("eventId는 양수여야 합니다.");
		}
		if (validHours <= 0) {
			throw new IllegalArgumentException("validHours는 양수여야 합니다.");
		}
		if (openAt == null || closeAt == null) {
			throw new IllegalArgumentException("회차 기간이 필요합니다.");
		}
		if (closeAt.isBefore(openAt)) {
			throw new IllegalArgumentException("회차 마감이 시작보다 앞설 수 없습니다.");
		}
	}

	public LocalDateTime validTo(LocalDateTime validFrom) {
		if (validFrom == null) {
			throw new IllegalArgumentException("validFrom이 필요합니다.");
		}
		return validFrom.plusHours(validHours);
	}
}
