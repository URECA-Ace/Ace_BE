package com.ace.coupon.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import com.ace.coupon.entity.Coupon;
import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.enums.CouponEventStatus;

import jakarta.persistence.EntityManager;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CouponEventRepositoryTest {

	@Autowired
	private CouponEventRepository couponEventRepository;

	@Autowired
	private EntityManager entityManager;

	@Test
	@DisplayName("오픈 시각에 도달하고 마감 전인 SCHEDULED 캠페인만 한 번 OPEN으로 전환한다")
	void opensOnlyScheduledEventsWithinIssuanceWindow() {
		LocalDateTime databaseNow = entityManager
				.createQuery("SELECT CURRENT_TIMESTAMP", Timestamp.class)
				.getSingleResult()
				.toLocalDateTime();
		Coupon coupon = persistCoupon(databaseNow);

		CouponEvent due = persistEvent(
				coupon, 1, databaseNow.minusMinutes(1), databaseNow.plusMinutes(10),
				CouponEventStatus.SCHEDULED, databaseNow);
		CouponEvent future = persistEvent(
				coupon, 2, databaseNow.plusMinutes(10), databaseNow.plusMinutes(20),
				CouponEventStatus.SCHEDULED, databaseNow);
		CouponEvent expired = persistEvent(
				coupon, 3, databaseNow.minusMinutes(20), databaseNow.minusMinutes(10),
				CouponEventStatus.SCHEDULED, databaseNow);
		CouponEvent alreadyOpen = persistEvent(
				coupon, 4, databaseNow.minusMinutes(1), databaseNow.plusMinutes(10),
				CouponEventStatus.OPEN, databaseNow);

		entityManager.flush();
		entityManager.clear();

		int firstUpdatedCount = couponEventRepository.openDueEvents(
				CouponEventStatus.SCHEDULED,
				CouponEventStatus.OPEN);
		int secondUpdatedCount = couponEventRepository.openDueEvents(
				CouponEventStatus.SCHEDULED,
				CouponEventStatus.OPEN);

		assertThat(firstUpdatedCount).isOne();
		assertThat(secondUpdatedCount).isZero();
		assertThat(findStatus(due.getId())).isEqualTo(CouponEventStatus.OPEN);
		assertThat(findStatus(future.getId())).isEqualTo(CouponEventStatus.SCHEDULED);
		assertThat(findStatus(expired.getId())).isEqualTo(CouponEventStatus.SCHEDULED);
		assertThat(findStatus(alreadyOpen.getId())).isEqualTo(CouponEventStatus.OPEN);
	}

	@Test
	@DisplayName("Redis 복구 대상 조회는 마감 전 SCHEDULED와 OPEN 캠페인만 반환한다")
	void findsOnlyActiveCampaignsForRedisRecovery() {
		LocalDateTime databaseNow = entityManager
				.createQuery("SELECT CURRENT_TIMESTAMP", Timestamp.class)
				.getSingleResult()
				.toLocalDateTime();
		Coupon coupon = persistCoupon(databaseNow);
		CouponEvent scheduled = persistEvent(
				coupon, 11, databaseNow.plusMinutes(10), databaseNow.plusMinutes(20),
				CouponEventStatus.SCHEDULED, databaseNow);
		CouponEvent open = persistEvent(
				coupon, 12, databaseNow.minusMinutes(1), databaseNow.plusMinutes(20),
				CouponEventStatus.OPEN, databaseNow);
		persistEvent(
				coupon, 13, databaseNow.minusMinutes(20), databaseNow.minusMinutes(1),
				CouponEventStatus.OPEN, databaseNow);
		persistEvent(
				coupon, 14, databaseNow.minusMinutes(1), databaseNow.plusMinutes(20),
				CouponEventStatus.SOLD_OUT, databaseNow);
		entityManager.flush();

		List<CouponEvent> result = couponEventRepository.findAllByStatusInAndCloseAtAfter(
				List.of(CouponEventStatus.SCHEDULED, CouponEventStatus.OPEN),
				databaseNow);

		assertThat(result).extracting(CouponEvent::getId)
				.containsExactlyInAnyOrder(scheduled.getId(), open.getId());
	}

	private Coupon persistCoupon(LocalDateTime now) {
		Coupon coupon = Coupon.builder()
				.couponName("예약 오픈 테스트 쿠폰")
				.type("DATA")
				.value(1L)
				.validHours(24)
				.createdAt(now)
				.build();
		entityManager.persist(coupon);
		return coupon;
	}

	private CouponEvent persistEvent(
			Coupon coupon,
			int round,
			LocalDateTime openAt,
			LocalDateTime closeAt,
			CouponEventStatus status,
			LocalDateTime now) {
		CouponEvent event = CouponEvent.builder()
				.coupon(coupon)
				.round(round)
				.openAt(openAt)
				.closeAt(closeAt)
				.totalStock(10_000)
				.remainingStock(10_000)
				.issuedQuantity(0)
				.perUserLimit(1)
				.status(status)
				.createdAt(now)
				.updatedAt(now.minusMinutes(1))
				.build();
		entityManager.persist(event);
		return event;
	}

	private CouponEventStatus findStatus(Long eventId) {
		return couponEventRepository.findById(eventId)
				.orElseThrow()
				.getStatus();
	}
}
