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

	private static final List<CouponEventStatus> CLOSE_TARGET_STATUSES = List.of(
			CouponEventStatus.OPEN,
			CouponEventStatus.SOLD_OUT);

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
	@DisplayName("마감 시각에 도달한 SCHEDULED, OPEN, SOLD_OUT 캠페인을 CLOSED로 전환한다")
	void closesAllDueCampaignStatuses() {
		LocalDateTime databaseNow = entityManager
				.createQuery("SELECT CURRENT_TIMESTAMP", Timestamp.class)
				.getSingleResult()
				.toLocalDateTime();
		Coupon coupon = persistCoupon(databaseNow);
		CouponEvent scheduled = persistEvent(
				coupon, 31, databaseNow.minusMinutes(20), databaseNow.minusMinutes(10),
				CouponEventStatus.SCHEDULED, databaseNow);
		CouponEvent open = persistEvent(
				coupon, 32, databaseNow.minusMinutes(20), databaseNow.minusMinutes(10),
				CouponEventStatus.OPEN, databaseNow);
		CouponEvent soldOut = persistEvent(
				coupon, 33, databaseNow.minusMinutes(20), databaseNow.minusMinutes(10),
				CouponEventStatus.SOLD_OUT, databaseNow);
		CouponEvent future = persistEvent(
				coupon, 34, databaseNow.minusMinutes(1), databaseNow.plusMinutes(10),
				CouponEventStatus.OPEN, databaseNow);
		entityManager.flush();
		entityManager.clear();

		int first = couponEventRepository.closeDueEvents(
				List.of(CouponEventStatus.SCHEDULED, CouponEventStatus.OPEN, CouponEventStatus.SOLD_OUT),
				CouponEventStatus.CLOSED);
		int duplicate = couponEventRepository.closeDueEvents(
				List.of(CouponEventStatus.SCHEDULED, CouponEventStatus.OPEN, CouponEventStatus.SOLD_OUT),
				CouponEventStatus.CLOSED);

		assertThat(first).isEqualTo(3);
		assertThat(duplicate).isZero();
		assertThat(findStatus(scheduled.getId())).isEqualTo(CouponEventStatus.CLOSED);
		assertThat(findStatus(open.getId())).isEqualTo(CouponEventStatus.CLOSED);
		assertThat(findStatus(soldOut.getId())).isEqualTo(CouponEventStatus.CLOSED);
		assertThat(findStatus(future.getId())).isEqualTo(CouponEventStatus.OPEN);
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
	@DisplayName("OPEN 캠페인만 조건부로 CLOSED 전환해 중복 마감을 방지한다")
	void closesOnlyOpenEventOnce() {
		LocalDateTime databaseNow = entityManager
				.createQuery("SELECT CURRENT_TIMESTAMP", Timestamp.class)
				.getSingleResult()
				.toLocalDateTime();
		Coupon coupon = persistCoupon(databaseNow);
		CouponEvent open = persistEvent(
				coupon, 21, databaseNow.minusMinutes(1), databaseNow.plusMinutes(20),
				CouponEventStatus.OPEN, databaseNow);
		CouponEvent scheduled = persistEvent(
				coupon, 22, databaseNow.plusMinutes(10), databaseNow.plusMinutes(20),
				CouponEventStatus.SCHEDULED, databaseNow);
		entityManager.flush();
		entityManager.clear();

		int first = couponEventRepository.closeOpenEvent(
				open.getId(), CouponEventStatus.OPEN, CouponEventStatus.CLOSED);
		int duplicate = couponEventRepository.closeOpenEvent(
				open.getId(), CouponEventStatus.OPEN, CouponEventStatus.CLOSED);
		int scheduledResult = couponEventRepository.closeOpenEvent(
				scheduled.getId(), CouponEventStatus.OPEN, CouponEventStatus.CLOSED);

		assertThat(first).isOne();
		assertThat(duplicate).isZero();
		assertThat(scheduledResult).isZero();
		assertThat(findStatus(open.getId())).isEqualTo(CouponEventStatus.CLOSED);
		assertThat(findStatus(scheduled.getId())).isEqualTo(CouponEventStatus.SCHEDULED);
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
				CouponEventStatus.OPEN, 0L, PageRequest.of(0, 100));

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
				CouponEventStatus.OPEN, 0L, PageRequest.of(0, 100));

		assertThat(eventIds).contains(issuing.getId());
		assertThat(eventIds).doesNotContain(pastClose.getId());
	}

	@Test
	@DisplayName("재고가 남아 있으면 소진 처리하지 않고, 0이 된 뒤에는 한 번만 전환한다")
	void marksSoldOutOnlyWhenStockIsExhausted() {
		LocalDateTime databaseNow = databaseNow();
		Coupon coupon = persistCoupon(databaseNow);
		CouponEvent event = persistEvent(
				coupon, 31, databaseNow.minusMinutes(1), databaseNow.plusMinutes(10),
				CouponEventStatus.OPEN, databaseNow);
		entityManager.flush();
		entityManager.clear();

		int beforeExhausted = couponEventRepository.markSoldOut(
				event.getId(), CouponEventStatus.OPEN, CouponEventStatus.SOLD_OUT);

		// 최종 스냅샷이 반영돼야 remaining_stock 이 0이 된다
		couponEventRepository.applyAggregateSnapshot(event.getId(), 10_000, 10_000);
		int firstUpdatedCount = couponEventRepository.markSoldOut(
				event.getId(), CouponEventStatus.OPEN, CouponEventStatus.SOLD_OUT);
		int secondUpdatedCount = couponEventRepository.markSoldOut(
				event.getId(), CouponEventStatus.OPEN, CouponEventStatus.SOLD_OUT);

		assertThat(beforeExhausted).isZero();
		assertThat(firstUpdatedCount).isOne();
		assertThat(secondUpdatedCount).isZero();
		assertThat(findStatus(event.getId())).isEqualTo(CouponEventStatus.SOLD_OUT);
	}

	@Test
	@DisplayName("마감 시각이 지난 회차만 마감하고 두 번 호출해도 한 번만 전환한다")
	void closesOnlyEventsPastCloseAt() {
		LocalDateTime databaseNow = databaseNow();
		Coupon coupon = persistCoupon(databaseNow);
		CouponEvent due = persistEvent(
				coupon, 32, databaseNow.minusMinutes(20), databaseNow.minusMinutes(10),
				CouponEventStatus.OPEN, databaseNow);
		CouponEvent issuing = persistEvent(
				coupon, 33, databaseNow.minusMinutes(1), databaseNow.plusMinutes(10),
				CouponEventStatus.OPEN, databaseNow);
		entityManager.flush();
		entityManager.clear();

		int firstUpdatedCount = couponEventRepository.markClosed(
				due.getId(), CLOSE_TARGET_STATUSES, CouponEventStatus.CLOSED, 10_000, 0);
		int secondUpdatedCount = couponEventRepository.markClosed(
				due.getId(), CLOSE_TARGET_STATUSES, CouponEventStatus.CLOSED, 10_000, 0);
		int issuingUpdatedCount = couponEventRepository.markClosed(
				issuing.getId(), CLOSE_TARGET_STATUSES, CouponEventStatus.CLOSED, 10_000, 0);

		assertThat(firstUpdatedCount).isOne();
		assertThat(secondUpdatedCount).isZero();
		assertThat(issuingUpdatedCount).isZero();
		assertThat(findStatus(due.getId())).isEqualTo(CouponEventStatus.CLOSED);
		assertThat(findStatus(issuing.getId())).isEqualTo(CouponEventStatus.OPEN);
	}

	@Test
	@DisplayName("마감 대상 조회는 마감 시각이 지난 미마감 회차만 반환한다")
	void findsOnlyDueAndNotClosedEventsAsCloseTargets() {
		LocalDateTime databaseNow = databaseNow();
		Coupon coupon = persistCoupon(databaseNow);
		// 오픈 스케줄러가 멈춰 한 번도 열리지 못한 회차도 마감돼야 한다
		CouponEvent neverOpened = persistEvent(
				coupon, 34, databaseNow.minusMinutes(30), databaseNow.minusMinutes(10),
				CouponEventStatus.SCHEDULED, databaseNow);
		CouponEvent dueOpen = persistEvent(
				coupon, 35, databaseNow.minusMinutes(30), databaseNow.minusMinutes(10),
				CouponEventStatus.OPEN, databaseNow);
		CouponEvent dueSoldOut = persistEvent(
				coupon, 36, databaseNow.minusMinutes(30), databaseNow.minusMinutes(10),
				CouponEventStatus.SOLD_OUT, databaseNow);
		CouponEvent alreadyClosed = persistEvent(
				coupon, 37, databaseNow.minusMinutes(30), databaseNow.minusMinutes(10),
				CouponEventStatus.CLOSED, databaseNow);
		CouponEvent issuing = persistEvent(
				coupon, 38, databaseNow.minusMinutes(1), databaseNow.plusMinutes(10),
				CouponEventStatus.OPEN, databaseNow);
		entityManager.flush();

		List<Long> eventIds = couponEventRepository.findCloseTargetEventIds(
				CLOSE_TARGET_STATUSES, 0L, PageRequest.of(0, 100));

		// SCHEDULED 는 Redis 현황 없이 마감할 수 있어 별도 목록으로 조회
		assertThat(eventIds).contains(dueOpen.getId(), dueSoldOut.getId());
		assertThat(eventIds).doesNotContain(
				neverOpened.getId(), alreadyClosed.getId(), issuing.getId());

		List<Long> neverOpenedIds = couponEventRepository.findCloseTargetEventIds(
				List.of(CouponEventStatus.SCHEDULED), 0L, PageRequest.of(0, 100));
		assertThat(neverOpenedIds).contains(neverOpened.getId());
	}

	@Test
	@DisplayName("집계가 최종 스냅샷과 다르면 마감하지 않는다")
	void doesNotCloseWhenAggregateDiffersFromSnapshot() {
		// 집계 반영이 거부된 상태에서 CLOSED 가 찍히면 검증이 확정 전 값을 신뢰하게 된다
		LocalDateTime databaseNow = databaseNow();
		Coupon coupon = persistCoupon(databaseNow);
		CouponEvent event = persistEvent(
				coupon, 39, databaseNow.minusMinutes(20), databaseNow.minusMinutes(10),
				CouponEventStatus.OPEN, databaseNow);
		entityManager.flush();
		entityManager.clear();

		// DB 집계는 0 인데 스냅샷은 400 이라고 주장하는 상황
		int mismatched = couponEventRepository.markClosed(
				event.getId(), CLOSE_TARGET_STATUSES, CouponEventStatus.CLOSED, 10_000, 400);
		// 재고 설정 자체가 다른 경우
		int wrongStock = couponEventRepository.markClosed(
				event.getId(), CLOSE_TARGET_STATUSES, CouponEventStatus.CLOSED, 9_999, 0);

		assertThat(mismatched).isZero();
		assertThat(wrongStock).isZero();
		assertThat(findStatus(event.getId())).isEqualTo(CouponEventStatus.OPEN);
	}

	@Test
	@DisplayName("한 번도 열리지 못한 회차는 SCHEDULED 상태에서만 마감된다")
	void closesOnlyScheduledEventsAsNeverOpened() {
		LocalDateTime databaseNow = databaseNow();
		Coupon coupon = persistCoupon(databaseNow);
		CouponEvent neverOpened = persistEvent(
				coupon, 40, databaseNow.minusMinutes(30), databaseNow.minusMinutes(10),
				CouponEventStatus.SCHEDULED, databaseNow);
		CouponEvent opened = persistEvent(
				coupon, 41, databaseNow.minusMinutes(30), databaseNow.minusMinutes(10),
				CouponEventStatus.OPEN, databaseNow);
		entityManager.flush();
		entityManager.clear();

		int scheduledUpdated = couponEventRepository.markScheduledClosed(
				neverOpened.getId(), CouponEventStatus.SCHEDULED, CouponEventStatus.CLOSED);
		int openedUpdated = couponEventRepository.markScheduledClosed(
				opened.getId(), CouponEventStatus.SCHEDULED, CouponEventStatus.CLOSED);

		assertThat(scheduledUpdated).isOne();
		assertThat(openedUpdated).isZero();
		assertThat(findStatus(neverOpened.getId())).isEqualTo(CouponEventStatus.CLOSED);
		assertThat(findStatus(opened.getId())).isEqualTo(CouponEventStatus.OPEN);
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
