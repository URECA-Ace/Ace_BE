package com.ace.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageRequest;

import com.ace.coupon.entity.Coupon;
import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.repository.CouponEventRepository;

class CouponEventQueryServiceTest {

	private CouponEventRepository couponEventRepository;
	private CouponEventQueryService couponEventQueryService;

	@BeforeEach
	void setUp() {
		couponEventRepository = Mockito.mock(CouponEventRepository.class);
		couponEventQueryService = new CouponEventQueryService(
				couponEventRepository,
				new CouponIssueRedisProperties(Duration.ofDays(7), ZoneId.of("Asia/Seoul")));
	}

	@Test
	@DisplayName("최근 생성된 발급 회차를 최대 5개 조회한다")
	void findsFiveRecentEvents() {
		Coupon coupon = Coupon.builder()
				.id(7L)
				.couponName("U+ 데이터 하루 무제한 쿠폰")
				.type("DATA_UNLIMITED")
				.value(0L)
				.validHours(24)
				.createdAt(LocalDateTime.of(2026, 8, 25, 9, 0))
				.build();
		CouponEvent event = CouponEvent.builder()
				.id(51L)
				.coupon(coupon)
				.round(3)
				.totalStock(10_000)
				.remainingStock(9_999)
				.issuedQuantity(1)
				.perUserLimit(1)
				.status(CouponEventStatus.OPEN)
				.openAt(LocalDateTime.of(2026, 8, 25, 10, 0))
				.closeAt(LocalDateTime.of(2026, 8, 25, 23, 59, 59))
				.createdAt(LocalDateTime.of(2026, 8, 25, 9, 30))
				.updatedAt(LocalDateTime.of(2026, 8, 25, 9, 30))
				.build();
		given(couponEventRepository.findRecentWithCoupon(PageRequest.of(0, 5)))
				.willReturn(List.of(event));

		var result = couponEventQueryService.findRecentEvents(null);

		assertThat(result).singleElement().satisfies(response -> {
			assertThat(response.eventId()).isEqualTo(51L);
			assertThat(response.couponName()).isEqualTo("U+ 데이터 하루 무제한 쿠폰");
			assertThat(response.round()).isEqualTo(3);
		});
		verify(couponEventRepository).findRecentWithCoupon(PageRequest.of(0, 5));
	}

	@Test
	@DisplayName("OPEN 상태의 최근 발급 회차만 최대 5개 조회한다")
	void findsFiveRecentOpenEvents() {
		given(couponEventRepository.findRecentWithCouponByStatus(
				CouponEventStatus.OPEN, PageRequest.of(0, 5)))
				.willReturn(List.of());

		var result = couponEventQueryService.findRecentEvents(CouponEventStatus.OPEN);

		assertThat(result).isEmpty();
		verify(couponEventRepository).findRecentWithCouponByStatus(
				CouponEventStatus.OPEN, PageRequest.of(0, 5));
	}
}
