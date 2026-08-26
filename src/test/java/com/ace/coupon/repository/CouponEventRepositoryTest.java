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
import org.springframework.data.domain.PageRequest;

import com.ace.coupon.entity.Coupon;
import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.enums.CampaignRedisInitializationStatus;
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

		List<CouponEvent> result = couponEventRepository.findRedisInitializationRecoveryCandidates(
				List.of(CouponEventStatus.SCHEDULED, CouponEventStatus.OPEN),
				databaseNow,
				CampaignRedisInitializationStatus.INITIALIZED,
				PageRequest.of(0, 100));

		assertThat(result).extracting(CouponEvent::getId)
				.containsExactlyInAnyOrder(scheduled.getId(), open.getId());
	}

	@Test
	@DisplayName("확정 수를 반영하면 남은 재고를 총 재고에서 빼서 함께 갱신")
	void appliesConfirmedQuantityAndDerivesRemainingStock() {
		LocalDateTime databaseNow = databaseNow();
		Coupon coupon = persistCoupon(databaseNow);
		CouponEvent event = persistEvent(
				coupon, 21, databaseNow.minusMinutes(1), databaseNow.plusMinutes(10),
				CouponEventStatus.OPEN, databaseNow);
		entityManager.flush();
		entityManager.clear();

		int updatedCount = couponEventRepository.applyAggregateSnapshot(event.getId(), 10_000, 400);

		assertThat(updatedCount).isOne();
		CouponEvent applied = findEvent(event.getId());
		assertThat(applied.getIssuedQuantity()).isEqualTo(400);
		assertThat(applied.getRemainingStock()).isEqualTo(9_600);
		// StockConsistencyCheck 의 검사식이 성립해야 함
		assertThat(applied.getIssuedQuantity() + applied.getRemainingStock())
				.isEqualTo(applied.getTotalStock());
	}

	@Test
	@DisplayName("더 낮은 확정 수가 늦게 도착해도 이미 반영된 집계를 되돌리지 않는다")
	void doesNotRollBackAggregateWhenStaleSnapshotArrives() {
		LocalDateTime databaseNow = databaseNow();
		Coupon coupon = persistCoupon(databaseNow);
		CouponEvent event = persistEvent(
				coupon, 22, databaseNow.minusMinutes(1), databaseNow.plusMinutes(10),
				CouponEventStatus.OPEN, databaseNow);
		entityManager.flush();
		entityManager.clear();

		couponEventRepository.applyAggregateSnapshot(event.getId(), 10_000, 400);
		int staleUpdatedCount = couponEventRepository.applyAggregateSnapshot(event.getId(), 10_000, 300);

		assertThat(staleUpdatedCount).isZero();
		CouponEvent applied = findEvent(event.getId());
		assertThat(applied.getIssuedQuantity()).isEqualTo(400);
		assertThat(applied.getRemainingStock()).isEqualTo(9_600);
	}

	@Test
	@DisplayName("같은 확정 수를 다시 반영해도 결과가 같다")
	void appliesSameConfirmedQuantityIdempotently() {
		LocalDateTime databaseNow = databaseNow();
		Coupon coupon = persistCoupon(databaseNow);
		CouponEvent event = persistEvent(
				coupon, 23, databaseNow.minusMinutes(1), databaseNow.plusMinutes(10),
				CouponEventStatus.OPEN, databaseNow);
		entityManager.flush();
		entityManager.clear();

		couponEventRepository.applyAggregateSnapshot(event.getId(), 10_000, 400);
		int secondUpdatedCount = couponEventRepository.applyAggregateSnapshot(event.getId(), 10_000, 400);

		assertThat(secondUpdatedCount).isOne();
		CouponEvent applied = findEvent(event.getId());
		assertThat(applied.getIssuedQuantity()).isEqualTo(400);
		assertThat(applied.getRemainingStock()).isEqualTo(9_600);
	}

	@Test
	@DisplayName("총 재고가 다른 현황은 다른 회차의 값으로 보고 반영X")
	void ignoresSnapshotWhoseTotalStockDiffers() {
		LocalDateTime databaseNow = databaseNow();
		Coupon coupon = persistCoupon(databaseNow);
		CouponEvent event = persistEvent(
				coupon, 24, databaseNow.minusMinutes(1), databaseNow.plusMinutes(10),
				CouponEventStatus.OPEN, databaseNow);
		entityManager.flush();
		entityManager.clear();

		int updatedCount = couponEventRepository.applyAggregateSnapshot(event.getId(), 9_999, 400);

		assertThat(updatedCount).isZero();
		CouponEvent untouched = findEvent(event.getId());
		assertThat(untouched.getIssuedQuantity()).isZero();
		assertThat(untouched.getRemainingStock()).isEqualTo(10_000);
	}

	@Test
	@DisplayName("집계 스냅샷 대상 조회는 요청한 상태의 회차만 반환")
	void findsSnapshotTargetsOfRequestedStatusOnly() {
		LocalDateTime databaseNow = databaseNow();
		Coupon coupon = persistCoupon(databaseNow);
		CouponEvent open = persistEvent(
				coupon, 25, databaseNow.minusMinutes(1), databaseNow.plusMinutes(10),
				CouponEventStatus.OPEN, databaseNow);
		CouponEvent closed = persistEvent(
				coupon, 26, databaseNow.minusMinutes(1), databaseNow.plusMinutes(10),
				CouponEventStatus.CLOSED, databaseNow);
		CouponEvent scheduled = persistEvent(
				coupon, 27, databaseNow.plusMinutes(10), databaseNow.plusMinutes(20),
				CouponEventStatus.SCHEDULED, databaseNow);
		entityManager.flush();

		List<Long> eventIds = couponEventRepository.findSnapshotTargetEventIds(
				CouponEventStatus.OPEN, PageRequest.of(0, 100));

		assertThat(eventIds).contains(open.getId());
		assertThat(eventIds).doesNotContain(closed.getId(), scheduled.getId());
	}

	@Test
	@DisplayName("집계 스냅샷 대상 조회는 마감 시각이 지난 회차를 제외")
	void excludesEventsPastCloseAtFromSnapshotTargets() {
		LocalDateTime databaseNow = databaseNow();
		Coupon coupon = persistCoupon(databaseNow);
		CouponEvent issuing = persistEvent(
				coupon, 28, databaseNow.minusMinutes(1), databaseNow.plusMinutes(10),
				CouponEventStatus.OPEN, databaseNow);
		CouponEvent pastClose = persistEvent(
				coupon, 29, databaseNow.minusMinutes(20), databaseNow.minusMinutes(10),
				CouponEventStatus.OPEN, databaseNow);
		entityManager.flush();

		List<Long> eventIds = couponEventRepository.findSnapshotTargetEventIds(
				CouponEventStatus.OPEN, PageRequest.of(0, 100));

		assertThat(eventIds).contains(issuing.getId());
		assertThat(eventIds).doesNotContain(pastClose.getId());
	}

	private LocalDateTime databaseNow() {
		return entityManager
				.createQuery("SELECT CURRENT_TIMESTAMP", Timestamp.class)
				.getSingleResult()
				.toLocalDateTime();
	}

	private CouponEvent findEvent(Long eventId) {
		return couponEventRepository.findById(eventId).orElseThrow();
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
