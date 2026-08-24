package com.ace.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageRequest;

import com.ace.coupon.entity.Coupon;
import com.ace.coupon.repository.CouponRepository;

class CouponQueryServiceTest {

	private CouponRepository couponRepository;
	private CouponQueryService couponQueryService;

	@BeforeEach
	void setUp() {
		couponRepository = Mockito.mock(CouponRepository.class);
		couponQueryService = new CouponQueryService(couponRepository);
	}

	@Test
	@DisplayName("검색어가 없으면 최신순으로 최대 6개를 조회한다")
	void findsSixRecentCoupons() {
		Coupon recentCoupon = coupon(6L, "최근 쿠폰");
		given(couponRepository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(0, 6)))
				.willReturn(List.of(recentCoupon));

		var result = couponQueryService.findCoupons("  ");

		assertThat(result).extracting(response -> response.couponId())
				.containsExactly(6L);
		Mockito.verify(couponRepository)
				.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(0, 6));
	}

	@Test
	@DisplayName("검색어가 있으면 앞뒤 공백을 제거하고 전체 제목에서 검색한다")
	void searchesAllCouponsByTitle() {
		Coupon matchedCoupon = coupon(2L, "U+ 데이터 하루 무제한 쿠폰");
		given(couponRepository
				.findAllByCouponNameContainingIgnoreCaseOrderByCreatedAtDescIdDesc("무제한"))
				.willReturn(List.of(matchedCoupon));

		var result = couponQueryService.findCoupons("  무제한  ");

		assertThat(result).extracting(response -> response.couponName())
				.containsExactly("U+ 데이터 하루 무제한 쿠폰");
		Mockito.verify(couponRepository)
				.findAllByCouponNameContainingIgnoreCaseOrderByCreatedAtDescIdDesc("무제한");
	}

	private Coupon coupon(Long id, String name) {
		return Coupon.builder()
				.id(id)
				.couponName(name)
				.type("DATA_UNLIMITED")
				.value(0L)
				.validHours(24)
				.createdAt(LocalDateTime.of(2026, 8, 24, 12, 0))
				.build();
	}
}
