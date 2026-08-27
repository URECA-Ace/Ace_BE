package com.ace.coupon.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;

import com.ace.coupon.entity.Coupon;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CouponRepositoryTest {

	@Autowired
	private CouponRepository couponRepository;

	@Test
	@DisplayName("LIKE 와일드카드를 이스케이프하면 퍼센트와 밑줄을 일반 문자로 검색한다")
	void searchesLiteralLikeWildcards() {
		LocalDateTime now = LocalDateTime.of(2099, 8, 18, 12, 0);
		Coupon literalWildcardCoupon = couponRepository.save(Coupon.builder()
				.couponName("PR37 %_ 리터럴 쿠폰")
				.type("DISCOUNT")
				.value(10L)
				.validHours(24)
				.createdAt(now)
				.build());
		couponRepository.save(Coupon.builder()
				.couponName("PR37 일반 쿠폰")
				.type("DISCOUNT")
				.value(10L)
				.validHours(24)
				.createdAt(now.minusSeconds(1))
				.build());
		couponRepository.flush();

		var result = couponRepository.searchByCouponName(
				"PR37 \\%\\_", PageRequest.of(0, 10));

		assertThat(result).extracting(Coupon::getId)
				.containsExactly(literalWildcardCoupon.getId());
	}
}
