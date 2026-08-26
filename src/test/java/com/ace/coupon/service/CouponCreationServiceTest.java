package com.ace.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import com.ace.coupon.dto.request.CouponCreateRequest;
import com.ace.coupon.dto.response.CouponSummaryResponse;
import com.ace.coupon.entity.Coupon;
import com.ace.coupon.enums.CouponType;
import com.ace.coupon.repository.CouponRepository;

class CouponCreationServiceTest {

	@Test
	@DisplayName("이름, 종류, 혜택 값, 유효 시간을 쿠폰 상품으로 저장한다")
	void createsCouponProduct() {
		CouponRepository repository = Mockito.mock(CouponRepository.class);
		given(repository.save(any(Coupon.class))).willAnswer(invocation -> {
			Coupon coupon = invocation.getArgument(0);
			ReflectionTestUtils.setField(coupon, "id", 51L);
			return coupon;
		});
		CouponCreationService service = new CouponCreationService(
				repository,
				Clock.fixed(
						Instant.parse("2026-08-21T06:00:00Z"),
						ZoneId.of("Asia/Seoul")));

		CouponSummaryResponse response = service.create(new CouponCreateRequest(
				" U+ 데이터 하루 무제한 쿠폰 ", CouponType.DATA_UNLIMITED, 0L, 24));

		assertThat(response.couponId()).isEqualTo(51L);
		ArgumentCaptor<Coupon> captor = ArgumentCaptor.forClass(Coupon.class);
		Mockito.verify(repository).save(captor.capture());
		assertThat(captor.getValue().getCouponName()).isEqualTo("U+ 데이터 하루 무제한 쿠폰");
		assertThat(captor.getValue().getType()).isEqualTo("DATA_UNLIMITED");
		assertThat(captor.getValue().getValidHours()).isEqualTo(24);
		assertThat(response.createdAt().getOffset().getTotalSeconds()).isEqualTo(9 * 60 * 60);
	}
}
