package com.ace.coupon.repository;

import com.ace.coupon.entity.CouponHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CouponHistoryRepository extends JpaRepository<CouponHistory, Long> {


	boolean existsByEventUid(String eventUid);

	Optional<CouponHistory> findByEventUid(String eventUid);


	List<CouponHistory> findAllByCouponIssue_IdOrderByOccurredAtAsc(Long issueId);

	/**
	 * StateMachineConsistencyCheck 복구 대상 선정용: 이벤트 하나에 속한 모든 발급 건의 이력을
	 * issue_id별로 묶어서 순서대로 훑을 수 있도록, issue_id -> 시간순으로 정렬해 통째로 가져온다.
	 * (StateMachineConsistencyCheck.SQL의 LAG 윈도우 함수가 하는 일을 애플리케이션에서 재현하기 위함)
	 */
	@Query("SELECT h FROM CouponHistory h WHERE h.couponIssue.couponEvent.id = :eventId "
			+ "ORDER BY h.couponIssue.id ASC, h.occurredAt ASC, h.id ASC")
	List<CouponHistory> findAllByCouponEventIdOrderByIssueAndTime(@Param("eventId") Long eventId);
}
