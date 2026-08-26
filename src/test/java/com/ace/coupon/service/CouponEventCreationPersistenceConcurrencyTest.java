package com.ace.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.ace.coupon.entity.Coupon;
import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.repository.CampaignRedisInitializationRepository;
import com.ace.coupon.repository.CouponEventRepository;
import com.ace.coupon.repository.CouponRepository;

@SpringBootTest
class CouponEventCreationPersistenceConcurrencyTest {

	@Autowired
	private CouponEventCreationPersistenceService persistenceService;

	@Autowired
	private CouponRepository couponRepository;

	@Autowired
	private CouponEventRepository couponEventRepository;

	@Autowired
	private CampaignRedisInitializationRepository initializationRepository;
	private Long createdCouponId;

	@AfterEach
	void cleanCampaignData() {
		if (createdCouponId == null) {
			return;
		}
		var events = couponEventRepository.findAllByCoupon_Id(createdCouponId);
		events.forEach(event -> initializationRepository.deleteById(event.getId()));
		couponEventRepository.deleteAll(events);
		couponRepository.deleteById(createdCouponId);
	}

	@Test
	@DisplayName("자동 회차와 명시 회차가 동시에 생성되어도 같은 쿠폰 행 락으로 한 건만 저장한다")
	void serializesAutomaticAndExplicitRoundCreation() throws Exception {
		LocalDateTime now = LocalDateTime.of(2099, 8, 18, 12, 0);
		Coupon coupon = couponRepository.saveAndFlush(Coupon.builder()
				.couponName("동시 생성 테스트 쿠폰")
				.type("DATA_UNLIMITED")
				.value(0L)
				.validHours(24)
				.createdAt(now)
				.build());
		createdCouponId = coupon.getId();
		LocalDateTime openAt = now.plusHours(1);
		LocalDateTime closeAt = now.plusHours(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<CouponEvent> automatic = executor.submit(() -> {
				ready.countDown();
				start.await(5, TimeUnit.SECONDS);
				return persistenceService.createNextRoundOrReuse(
						coupon.getId(), 10_000, openAt, closeAt,
						CouponEventStatus.SCHEDULED, now);
			});
			Future<CouponEvent> explicit = executor.submit(() -> {
				ready.countDown();
				start.await(5, TimeUnit.SECONDS);
				return persistenceService.createOrReuse(
						coupon.getId(), 1, 10_000, openAt, closeAt,
						CouponEventStatus.SCHEDULED, now);
			});

			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			CouponEvent automaticResult = automatic.get(10, TimeUnit.SECONDS);
			CouponEvent explicitResult = explicit.get(10, TimeUnit.SECONDS);

			assertThat(automaticResult.getId()).isEqualTo(explicitResult.getId());
			assertThat(couponEventRepository.findByCoupon_IdAndRound(coupon.getId(), 1))
					.map(CouponEvent::getId)
					.contains(automaticResult.getId());
			assertThat(couponEventRepository.countByCoupon_Id(coupon.getId())).isOne();
			assertThat(couponEventRepository.findAllByCoupon_Id(coupon.getId()))
					.extracting(CouponEvent::getId)
					.containsExactly(automaticResult.getId());
		}
	}
}
