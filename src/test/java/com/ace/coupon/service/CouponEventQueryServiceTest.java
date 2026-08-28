package com.ace.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
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
import com.ace.coupon.repository.CouponEventRepository;

class CouponEventQueryServiceTest {

	private CouponEventRepository couponEventRepository;
	private CouponEventQueryService couponEventQueryService;

	@BeforeEach
	void setUp() {
		couponEventRepository = Mockito.mock(CouponEventRepository.class);
		couponEventQueryService = new CouponEventQueryService(
				couponEventRepository,
				Clock.fixed(Instant.parse("2026-08-25T02:00:00Z"), ZoneId.of("Asia/Seoul")));
	}

	@Test
	@DisplayName("최근 생성된 발급 회차를 기본 6개까지 조회한다")
	void findsRecentEventsUsingDefaultSize() {
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
		given(couponEventRepository.findRecentWithCoupon(PageRequest.of(0, 6)))
				.willReturn(List.of(event));

		var result = couponEventQueryService.findRecentEvents(null);

		assertThat(result).singleElement().satisfies(response -> {
			assertThat(response.eventId()).isEqualTo(51L);
			assertThat(response.couponName()).isEqualTo("U+ 데이터 하루 무제한 쿠폰");
			assertThat(response.round()).isEqualTo(3);
		});
		verify(couponEventRepository).findRecentWithCoupon(PageRequest.of(0, 6));
	}

	@Test
	@DisplayName("OPEN 상태의 최근 발급 회차를 요청한 개수만큼 조회한다")
	void findsRecentOpenEventsUsingRequestedSize() {
		given(couponEventRepository.findRecentWithCouponByStatus(
				CouponEventStatus.OPEN, PageRequest.of(0, 10)))
				.willReturn(List.of());

		var result = couponEventQueryService.findRecentEvents(CouponEventStatus.OPEN, 10);

		assertThat(result).isEmpty();
		verify(couponEventRepository).findRecentWithCouponByStatus(
				CouponEventStatus.OPEN, PageRequest.of(0, 10));
	}

	@Test
	@DisplayName("마감 시각이 지났어도 Drain 전이면 DB에 확정된 OPEN 상태를 반환한다")
	void returnsStoredOpenStatusUntilDrainIsCompleted() {
		Coupon coupon = Coupon.builder()
				.id(8L)
				.couponName("예약 마감 조회 쿠폰")
				.type("DATA_UNLIMITED")
				.value(0L)
				.validHours(24)
				.createdAt(LocalDateTime.of(2026, 8, 25, 8, 0))
				.build();
		CouponEvent expiredOpenEvent = CouponEvent.builder()
				.id(52L)
				.coupon(coupon)
				.round(1)
				.totalStock(10_000)
				.remainingStock(10_000)
				.issuedQuantity(0)
				.perUserLimit(1)
				.status(CouponEventStatus.OPEN)
				.openAt(LocalDateTime.of(2026, 8, 25, 9, 0))
				.closeAt(LocalDateTime.of(2026, 8, 25, 10, 0))
				.createdAt(LocalDateTime.of(2026, 8, 25, 8, 30))
				.updatedAt(LocalDateTime.of(2026, 8, 25, 9, 0, 1))
				.build();
		given(couponEventRepository.findRecentWithCouponByStatus(
				CouponEventStatus.OPEN, PageRequest.of(0, 10)))
				.willReturn(List.of(expiredOpenEvent));

		var result = couponEventQueryService.findRecentEvents(CouponEventStatus.OPEN, 10);

		assertThat(result).singleElement().satisfies(response -> {
			assertThat(response.status()).isEqualTo(CouponEventStatus.OPEN);
			assertThat(response.statusChangedAt())
					.isEqualTo(OffsetDateTime.parse("2026-08-25T09:00:01+09:00"));
		});
	}
}
