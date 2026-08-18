package com.ace.coupon.redis;

/**
 * 캠페인 단위 Redis Cluster 동일 슬롯 키 규칙.
 */
public final class CouponRedisKeys {

	// Bitmap 키당 1 MiB 제한
	static final long BITMAP_SEGMENT_BITS = 8L * 1024 * 1024;

	private static final String PREFIX = "coupon:";

	private CouponRedisKeys() {
	}

	public static CampaignKeys campaign(Long campaignId) {
		if (campaignId == null || campaignId <= 0) {
			throw new IllegalArgumentException("campaignId는 양수여야 합니다.");
		}
		return new CampaignKeys(campaignId);
	}

	public record CampaignKeys(Long campaignId) {

		private String base() {
			// 중괄호 내부 캠페인 식별자 기반 Cluster hash-tag
			return PREFIX + "{campaign:" + campaignId + "}:";
		}

		public String metadata() {
			return base() + "meta";
		}

		public String stock() {
			return base() + "stock";
		}

		public String sequence() {
			return base() + "sequence";
		}

		public String requests() {
			return base() + "requests";
		}

		public String issueStream() {
			return base() + "issue-stream";
		}

		public BitmapLocation bitmap(Long userId) {
			if (userId == null || userId <= 0) {
				throw new IllegalArgumentException("userId는 양수여야 합니다.");
			}

			long zeroBasedUserId = userId - 1;
			long segment = zeroBasedUserId / BITMAP_SEGMENT_BITS;
			long offset = zeroBasedUserId % BITMAP_SEGMENT_BITS;
			return new BitmapLocation(base() + "issued:bitmap:" + segment, segment, offset);
		}
	}

	public record BitmapLocation(String key, long segment, long offset) {
	}
}
