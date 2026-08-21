package com.ace.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.Duration;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import com.ace.coupon.dto.request.CouponCreateRequest;
import com.ace.coupon.dto.response.CouponCreateResponse;
import com.ace.coupon.entity.Coupon;
import com.ace.coupon.redis.CouponIssueRedisProperties;
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
				new CouponIssueRedisProperties(Duration.ofDays(7), ZoneId.of("Asia/Seoul")));

		CouponCreateResponse response = service.create(new CouponCreateRequest(
				" U+ 데이터 하루 무제한 쿠폰 ", " DATA_UNLIMITED ", 0L, 24));

		assertThat(response.couponId()).isEqualTo(51L);
		ArgumentCaptor<Coupon> captor = ArgumentCaptor.forClass(Coupon.class);
		Mockito.verify(repository).save(captor.capture());
		assertThat(captor.getValue().getCouponName()).isEqualTo("U+ 데이터 하루 무제한 쿠폰");
		assertThat(captor.getValue().getType()).isEqualTo("DATA_UNLIMITED");
		assertThat(captor.getValue().getValidHours()).isEqualTo(24);
	}
}
