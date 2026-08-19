package com.ace.coupon.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ace.coupon.entity.Coupon;
import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.repository.CouponEventRepository;

@ExtendWith(MockitoExtension.class)
class CampaignMetadataCacheTest {

	private static final LocalDateTime OPEN_AT = LocalDateTime.of(2026, 8, 19, 12, 0);
	private static final LocalDateTime CLOSE_AT = OPEN_AT.plusHours(1);

	@Mock
	private CouponEventRepository couponEventRepository;

	private CampaignMetadataCache cache;

	@BeforeEach
	void setUp() {
		cache = new CampaignMetadataCache(
				couponEventRepository,
				new CouponIssuePersistenceProperties(
						null, null, null, null, null, null, null, true));
	}

	private CouponEvent event(long eventId, int validHours) {
		Coupon coupon = Coupon.builder()
				.couponName("정오 쿠폰")
				.type("FIXED")
				.value(1000L)
				.validHours(validHours)
				.createdAt(OPEN_AT)
				.build();

		return CouponEvent.builder()
				.id(eventId)
				.coupon(coupon)
				.round(1)
				.openAt(OPEN_AT)
				.closeAt(CLOSE_AT)
				.totalStock(10_000)
				.remainingStock(10_000)
				.issuedQuantity(0)
				.perUserLimit(1)
				.createdAt(OPEN_AT)
				.updatedAt(OPEN_AT)
				.build();
	}

	@Test
	@DisplayName("회차 정보를 읽어 온다")
	void loadsMetadata() {
		given(couponEventRepository.findWithCouponById(1L)).willReturn(Optional.of(event(1L, 168)));

		CampaignMetadata metadata = cache.get(1L);

		assertThat(metadata.eventId()).isEqualTo(1L);
		assertThat(metadata.validHours()).isEqualTo(168);
		assertThat(metadata.openAt()).isEqualTo(OPEN_AT);
		assertThat(metadata.closeAt()).isEqualTo(CLOSE_AT);
	}

	@Test
	@DisplayName("두 번째 호출은 조회하지 않는다")
	void queriesOnlyOnce() {
		given(couponEventRepository.findWithCouponById(1L)).willReturn(Optional.of(event(1L, 168)));

		cache.get(1L);
		cache.get(1L);
		cache.get(1L);

		verify(couponEventRepository, times(1)).findWithCouponById(1L);
	}

	@Test
	@DisplayName("회차가 없으면 예외를 던진다")
	void failsWhenEventMissing() {
		given(couponEventRepository.findWithCouponById(99L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> cache.get(99L))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("99");
	}

	@Test
	@DisplayName("조회에 실패한 회차는 캐시에 남기지 않는다")
	void doesNotCacheFailure() {
		given(couponEventRepository.findWithCouponById(99L))
				.willReturn(Optional.empty())
				.willReturn(Optional.of(event(99L, 24)));

		assertThatThrownBy(() -> cache.get(99L)).isInstanceOf(IllegalStateException.class);

		assertThat(cache.get(99L).validHours()).isEqualTo(24);
		verify(couponEventRepository, times(2)).findWithCouponById(99L);
	}

	@Test
	@DisplayName("validTo는 유효시간을 더해 계산한다")
	void calculatesValidTo() {
		given(couponEventRepository.findWithCouponById(anyLong()))
				.willReturn(Optional.of(event(1L, 168)));
		LocalDateTime validFrom = LocalDateTime.of(2026, 8, 19, 12, 0, 0, 123_456_000);

		LocalDateTime validTo = cache.get(1L).validTo(validFrom);

		assertThat(validTo).isEqualTo(validFrom.plusHours(168));
	}

	@Test
	@DisplayName("캐시를 끄면 매번 조회한다(측정용 스위치가 실제로 동작하는지)")
	void bypassesCacheWhenDisabled() {
		given(couponEventRepository.findWithCouponById(1L)).willReturn(Optional.of(event(1L, 168)));
		CampaignMetadataCache noCache = new CampaignMetadataCache(
				couponEventRepository,
				new CouponIssuePersistenceProperties(
						null, null, null, null, null, null, null, false));

		noCache.get(1L);
		noCache.get(1L);
		noCache.get(1L);

		verify(couponEventRepository, times(3)).findWithCouponById(1L);
	}
}
