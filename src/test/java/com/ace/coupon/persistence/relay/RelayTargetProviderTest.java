package com.ace.coupon.persistence.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.redis.CouponRedisKeys;
import com.ace.coupon.repository.CouponEventRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RelayTargetProviderTest {

	@Mock
	private CouponEventRepository couponEventRepository;

	@Mock
	private StringRedisTemplate redisTemplate;

	private RelayTargetProvider provider;

	@BeforeEach
	void setUp() {
		provider = new RelayTargetProvider(
				couponEventRepository,
				redisTemplate,
				new CouponIssueRedisProperties(Duration.ofDays(7), ZoneId.of("Asia/Seoul")));
	}

	private String streamKey(long campaignId) {
		return CouponRedisKeys.campaign(campaignId).issueStream();
	}

	@Test
	@DisplayName("초기화된 캠페인만 소비 대상으로 삼는다(아직 시작 안 한 회차에 빈 Stream 을 만들면 안 된다)")
	void keepsOnlyInitializedCampaigns() {
		given(couponEventRepository.findConsumableEventIds(any(), any())).willReturn(List.of(1L, 2L, 3L));
		given(redisTemplate.hasKey(streamKey(1L))).willReturn(true);
		given(redisTemplate.hasKey(streamKey(2L))).willReturn(false);
		given(redisTemplate.hasKey(streamKey(3L))).willReturn(true);

		assertThat(provider.campaignIds()).containsExactly(1L, 3L);
	}

	@Test
	@DisplayName("보존기간이 지난 회차는 조회 범위에서 뺀다")
	void limitsRangeByRetention() {
		given(couponEventRepository.findConsumableEventIds(any(), any())).willReturn(List.of());

		provider.campaignIds();

		ArgumentCaptor<LocalDateTime> now = ArgumentCaptor.forClass(LocalDateTime.class);
		ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
		org.mockito.Mockito.verify(couponEventRepository)
				.findConsumableEventIds(now.capture(), since.capture());
		assertThat(Duration.between(since.getValue(), now.getValue())).isEqualTo(Duration.ofDays(7));
	}

	@Test
	@DisplayName("소비할 회차가 없으면 빈 목록")
	void returnsEmptyWhenNoEvents() {
		given(couponEventRepository.findConsumableEventIds(any(), any())).willReturn(List.of());

		assertThat(provider.campaignIds()).isEmpty();
	}
}
