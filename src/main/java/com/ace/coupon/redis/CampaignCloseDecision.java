package com.ace.coupon.redis;

import java.time.Instant;

public record CampaignCloseDecision(
		CampaignCloseResult result,
		Instant closedAt) {
}
