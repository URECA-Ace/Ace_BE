package com.ace.coupon.redis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CouponRedisKeysTest {

	@Test
	@DisplayName("캠페인의 모든 Redis 키는 동일한 Cluster hash-tag를 사용한다")
	void usesSameCampaignHashTag() {
		CouponRedisKeys.CampaignKeys keys = CouponRedisKeys.campaign(13L);

		assertThat(keys.metadata()).contains("{campaign:13}");
		assertThat(keys.stock()).contains("{campaign:13}");
		assertThat(keys.sequence()).contains("{campaign:13}");
		assertThat(keys.requests()).contains("{campaign:13}");
		assertThat(keys.issueStream()).contains("{campaign:13}");
		assertThat(keys.bitmap(Long.MAX_VALUE).key()).contains("{campaign:13}");
	}

	@Test
	@DisplayName("큰 사용자 식별자는 고정 크기 Bitmap 세그먼트로 분리한다")
	void segmentsLargeUserId() {
		CouponRedisKeys.CampaignKeys keys = CouponRedisKeys.campaign(13L);

		CouponRedisKeys.BitmapLocation first = keys.bitmap(1L);
		CouponRedisKeys.BitmapLocation nextSegment = keys.bitmap(CouponRedisKeys.BITMAP_SEGMENT_BITS + 1);

		assertThat(first.key()).endsWith("issued:bitmap:0");
		assertThat(first.segment()).isZero();
		assertThat(first.offset()).isZero();
		assertThat(nextSegment.key()).endsWith("issued:bitmap:1");
		assertThat(nextSegment.segment()).isOne();
		assertThat(nextSegment.offset()).isZero();
	}
}
