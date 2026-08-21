package com.ace.coupon.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import com.ace.coupon.entity.CampaignRedisInitialization;
import com.ace.coupon.entity.Coupon;
import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.enums.CampaignRedisInitializationStatus;
import com.ace.coupon.enums.CouponEventStatus;

import jakarta.persistence.EntityManager;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CampaignRedisInitializationRepositoryTest {

	@Autowired
	private CampaignRedisInitializationRepository repository;

	@Autowired
	private EntityManager entityManager;

	@Test
	@DisplayName("기존 상태 행의 Redis 초기화 시도를 반복 기록하면 매번 갱신에 성공한다")
	void recordsRepeatedAttemptsForExistingState() {
		LocalDateTime now = LocalDateTime.now().withNano(0);
		Coupon coupon = Coupon.builder()
				.couponName("Redis 초기화 상태 테스트 쿠폰")
				.type("DATA")
				.value(1L)
				.validHours(24)
				.createdAt(now)
				.build();
		entityManager.persist(coupon);

		CouponEvent event = CouponEvent.builder()
				.coupon(coupon)
				.round(1)
				.openAt(now.plusMinutes(1))
				.closeAt(now.plusHours(1))
				.totalStock(10_000)
				.remainingStock(10_000)
				.issuedQuantity(0)
				.perUserLimit(1)
				.status(CouponEventStatus.SCHEDULED)
				.createdAt(now)
				.updatedAt(now)
				.build();
		entityManager.persist(event);
		entityManager.persist(CampaignRedisInitialization.pending(event.getId(), now));
		entityManager.flush();

		int firstAffectedRows = repository.recordAttempt(event.getId(), now.plusSeconds(1));
		int secondAffectedRows = repository.recordAttempt(event.getId(), now.plusSeconds(2));
		entityManager.flush();
		entityManager.clear();

		CampaignRedisInitialization stored = repository.findById(event.getId()).orElseThrow();
		assertThat(firstAffectedRows).isPositive();
		assertThat(secondAffectedRows).isPositive();
		assertThat(stored.getStatus()).isEqualTo(CampaignRedisInitializationStatus.PENDING);
		assertThat(stored.getAttemptCount()).isEqualTo(2);
		assertThat(stored.getLastAttemptedAt()).isEqualTo(now.plusSeconds(2));
	}
}
