package com.ace.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.ace.consistency.check.CouponExpirationLagConsistencyCheck;
import com.ace.consistency.check.ConsistencyCheckIntegrationTestBase;
import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.Scope;
import com.ace.coupon.entity.Coupon;
import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.entity.CouponHistory;
import com.ace.coupon.entity.CouponIssue;
import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.enums.CouponIssueStatus;
import com.ace.coupon.repository.CouponHistoryRepository;
import com.ace.coupon.repository.CouponIssueRepository;
import com.ace.coupon.repository.CouponStateIdempotencyRepository;
import com.ace.user.entity.User;

import jakarta.persistence.EntityManager;

class CouponExpirationServiceIntegrationTest extends ConsistencyCheckIntegrationTestBase {

	@Autowired private CouponExpirationService couponExpirationService;
	@Autowired private CouponIssueRepository couponIssueRepository;
	@Autowired private CouponHistoryRepository couponHistoryRepository;
	@Autowired private CouponStateIdempotencyRepository idempotencyRepository;
	@Autowired private CouponExpirationLagConsistencyCheck expirationLagCheck;
	@Autowired private TransactionTemplate transactionTemplate;
	@Autowired private EntityManager em;

	private Long testEventId;
	private Long expiredIssuedId;
	private Long validIssuedId;
	private Long expiredUsedId;

	@BeforeEach
	void setUp() {
		transactionTemplate.executeWithoutResult(status -> {
			User user1 = User.builder()
					.email("user1@ace.com")
					.name("유저1")
					.createdAt(LocalDateTime.now())
					.build();
			User user2 = User.builder()
					.email("user2@ace.com")
					.name("유저2")
					.createdAt(LocalDateTime.now())
					.build();
			User user3 = User.builder()
					.email("user3@ace.com")
					.name("유저3")
					.createdAt(LocalDateTime.now())
					.build();
			em.persist(user1);
			em.persist(user2);
			em.persist(user3);

			Coupon coupon = Coupon.builder()
					.couponName("만료 테스트 쿠폰")
					.type("DISCOUNT")
					.value(1000L)
					.validHours(24)
					.createdAt(LocalDateTime.now())
					.build();
			em.persist(coupon);

			CouponEvent event = CouponEvent.builder()
					.coupon(coupon)
					.round(1)
					.totalStock(100)
					.remainingStock(100)
					.issuedQuantity(3)
					.perUserLimit(1)
					.status(CouponEventStatus.OPEN)
					.openAt(LocalDateTime.now().minusDays(10))
					.closeAt(LocalDateTime.now().plusDays(7))
					.createdAt(LocalDateTime.now().minusDays(10))
					.updatedAt(LocalDateTime.now().minusDays(10))
					.build();
			em.persist(event);

			CouponIssue expiredIssued = CouponIssue.builder()
					.couponEvent(event)
					.user(user1)
					.issueSequence(1)
					.requestId(UUID.randomUUID().toString())
					.status(CouponIssueStatus.ISSUED)
					.issuedAt(LocalDateTime.now().minusDays(5))
					.validFrom(LocalDateTime.now().minusDays(5))
					.validTo(LocalDateTime.now().minusDays(1))
					.createdAt(LocalDateTime.now().minusDays(5))
					.build();
			em.persist(expiredIssued);

			CouponIssue validIssued = CouponIssue.builder()
					.couponEvent(event)
					.user(user2)
					.issueSequence(2)
					.requestId(UUID.randomUUID().toString())
					.status(CouponIssueStatus.ISSUED)
					.issuedAt(LocalDateTime.now())
					.validFrom(LocalDateTime.now())
					.validTo(LocalDateTime.now().plusDays(3))
					.createdAt(LocalDateTime.now())
					.build();
			em.persist(validIssued);

			CouponIssue expiredUsed = CouponIssue.builder()
					.couponEvent(event)
					.user(user3)
					.issueSequence(3)
					.requestId(UUID.randomUUID().toString())
					.status(CouponIssueStatus.USED)
					.issuedAt(LocalDateTime.now().minusDays(5))
					.validFrom(LocalDateTime.now().minusDays(5))
					.validTo(LocalDateTime.now().minusDays(1))
					.usedAt(LocalDateTime.now().minusDays(2))
					.createdAt(LocalDateTime.now().minusDays(5))
					.build();
			em.persist(expiredUsed);

			testEventId = event.getId();
			expiredIssuedId = expiredIssued.getId();
			validIssuedId = validIssued.getId();
			expiredUsedId = expiredUsed.getId();
		});
	}

	@Override
	@AfterEach
	protected void tearDown() {
		transactionTemplate.executeWithoutResult(status -> {
			idempotencyRepository.deleteAll();
			couponHistoryRepository.deleteAll();
			couponIssueRepository.deleteAll();
			em.createNativeQuery("DELETE FROM coupon_event").executeUpdate();
			em.createNativeQuery("DELETE FROM coupon").executeUpdate();
			em.createNativeQuery("DELETE FROM user").executeUpdate();
		});
	}

	@Test
	@DisplayName("복합 상태 환경에서 오직 유효기간이 지난 ISSUED 쿠폰만 EXPIRED로 변경되고 정합성 검증기를 통과한다")
	void expireDueCoupons_onlyExpiredIssuedTransits_andPassesConsistencyCheck() {
		int expiredCount = couponExpirationService.expireDueCoupons(100);

		assertThat(expiredCount).isEqualTo(1);

		transactionTemplate.executeWithoutResult(status -> {
			CouponIssue issue1 = couponIssueRepository.findById(expiredIssuedId).orElseThrow();
			assertThat(issue1.getStatus()).isEqualTo(CouponIssueStatus.EXPIRED);

			CouponIssue issue2 = couponIssueRepository.findById(validIssuedId).orElseThrow();
			assertThat(issue2.getStatus()).isEqualTo(CouponIssueStatus.ISSUED);

			CouponIssue issue3 = couponIssueRepository.findById(expiredUsedId).orElseThrow();
			assertThat(issue3.getStatus()).isEqualTo(CouponIssueStatus.USED);

			List<CouponHistory> histories = couponHistoryRepository
					.findAllByCouponIssue_IdOrderByOccurredAtAsc(expiredIssuedId);
			assertThat(histories).hasSize(1);
			CouponHistory history = histories.get(0);
			assertThat(history.getFromStatus()).isEqualTo(CouponIssueStatus.ISSUED);
			assertThat(history.getToStatus()).isEqualTo(CouponIssueStatus.EXPIRED);
			assertThat(history.getActor()).isEqualTo("SYSTEM");
		});

		ConsistencyCheck.CheckOutcome outcome = expirationLagCheck.check(Scope.ofEvent(testEventId));
		assertThat(outcome.isPass()).isTrue();
	}
}
