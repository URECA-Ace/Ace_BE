package com.ace.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.IntStream;

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
		given(couponEventRepository.findRecentWithCoupon(PageRequest.of(0, 100)))
				.willReturn(List.of());

		var result = couponEventQueryService.findRecentEvents(CouponEventStatus.OPEN, 10);

		assertThat(result).isEmpty();
		verify(couponEventRepository).findRecentWithCoupon(PageRequest.of(0, 100));
	}

	@Test
	@DisplayName("첫 페이지가 전부 다른 상태여도 뒤 페이지의 대상 회차를 찾아낸다")
	void scansBeyondFirstPageWhenStatusFilterApplied() {
		// 첫 페이지를 최근 회차가 가득 채우면, 조건에 맞는 회차는 뒤 페이지로 밀린다.
		// 첫 페이지만 보고 자르면 DB 에 있는데도 빈 결과가 나간다
		given(couponEventRepository.findRecentWithCoupon(PageRequest.of(0, 100)))
				.willReturn(closedEvents(100));
		given(couponEventRepository.findRecentWithCoupon(PageRequest.of(1, 100)))
				.willReturn(List.of(openEvent(9_001L)));

		var result = couponEventQueryService.findRecentEvents(CouponEventStatus.OPEN, 6);

		assertThat(result).singleElement().satisfies(response -> {
			assertThat(response.eventId()).isEqualTo(9_001L);
			assertThat(response.status()).isEqualTo(CouponEventStatus.OPEN);
		});
	}

	@Test
	@DisplayName("요청한 개수를 채우면 남은 페이지는 읽지 않는다")
	void stopsScanningOnceRequestedSizeIsFilled() {
		given(couponEventRepository.findRecentWithCoupon(PageRequest.of(0, 100)))
				.willReturn(closedEvents(100));

		var result = couponEventQueryService.findRecentEvents(CouponEventStatus.CLOSED, 3);

		assertThat(result).hasSize(3);
		verify(couponEventRepository, never()).findRecentWithCoupon(PageRequest.of(1, 100));
	}

	private List<CouponEvent> closedEvents(int count) {
		Coupon coupon = filterCoupon();
		return IntStream.range(0, count)
				.mapToObj(index -> CouponEvent.builder()
						.id(1_000L + index)
						.coupon(coupon)
						.round(index + 1)
						.totalStock(10_000)
						.remainingStock(0)
						.issuedQuantity(10_000)
						.perUserLimit(1)
						.status(CouponEventStatus.CLOSED)
						.openAt(LocalDateTime.of(2026, 8, 25, 9, 0))
						.closeAt(LocalDateTime.of(2026, 8, 25, 10, 0))
						.createdAt(LocalDateTime.of(2026, 8, 25, 8, 30))
						.updatedAt(LocalDateTime.of(2026, 8, 25, 10, 0, 1))
						.build())
				.toList();
	}

	private CouponEvent openEvent(long eventId) {
		return CouponEvent.builder()
				.id(eventId)
				.coupon(filterCoupon())
				.round(1)
				.totalStock(10_000)
				.remainingStock(10_000)
				.issuedQuantity(0)
				.perUserLimit(1)
				.status(CouponEventStatus.OPEN)
				.openAt(LocalDateTime.of(2026, 8, 25, 9, 0))
				.closeAt(LocalDateTime.of(2026, 8, 25, 12, 0))
				.createdAt(LocalDateTime.of(2026, 8, 25, 8, 0))
				.updatedAt(LocalDateTime.of(2026, 8, 25, 9, 0, 1))
				.build();
	}

	private Coupon filterCoupon() {
		return Coupon.builder()
				.id(9L)
				.couponName("상태 필터 테스트 쿠폰")
				.type("DATA_UNLIMITED")
				.value(0L)
				.validHours(24)
				.createdAt(LocalDateTime.of(2026, 8, 25, 8, 0))
				.build();
	}

	@Test
	@DisplayName("DB 상태가 OPEN이어도 마감 시각이 지났으면 CLOSED로 조회한다")
	void returnsEffectiveClosedStatusWithoutUpdatingDuringQuery() {
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
		given(couponEventRepository.findRecentWithCoupon(PageRequest.of(0, 100)))
				.willReturn(List.of(expiredOpenEvent));

		var result = couponEventQueryService.findRecentEvents(CouponEventStatus.CLOSED, 10);

		assertThat(result).singleElement().satisfies(response -> {
			assertThat(response.status()).isEqualTo(CouponEventStatus.CLOSED);
			assertThat(response.statusChangedAt())
					.isEqualTo(OffsetDateTime.parse("2026-08-25T10:00:00+09:00"));
		});
	}
}
