package com.ace.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageRequest;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.entity.CouponIssue;
import com.ace.coupon.repository.CouponEventRepository;
import com.ace.coupon.repository.CouponIssueRepository;
import com.ace.user.entity.User;

class CouponIssuanceLogServiceTest {

	private static final long EVENT_ID = 60L;
	private static final ZoneId ZONE_ID = ZoneId.of("Asia/Seoul");

	private CouponEventRepository couponEventRepository;
	private CouponIssueRepository couponIssueRepository;
	private CouponIssuanceLogService couponIssuanceLogService;

	@BeforeEach
	void setUp() {
		couponEventRepository = Mockito.mock(CouponEventRepository.class);
		couponIssueRepository = Mockito.mock(CouponIssueRepository.class);
		couponIssuanceLogService = new CouponIssuanceLogService(
				couponEventRepository,
				couponIssueRepository,
				Clock.fixed(Instant.parse("2026-08-27T01:00:00Z"), ZONE_ID));
	}

	@Test
	@DisplayName("DB에 확정된 발급 로그를 순번 커서 다음부터 오름차순으로 반환한다")
	void findsConfirmedLogsAfterSequence() {
		CouponIssue first = issue(101L, 21, "2026-08-27T10:00:00", "2026-08-27T10:00:01");
		CouponIssue second = issue(102L, 22, "2026-08-27T10:00:00", "2026-08-27T10:00:02");
		given(couponEventRepository.existsById(EVENT_ID)).willReturn(true);
		given(couponIssueRepository
				.findByCouponEvent_IdAndIssueSequenceGreaterThanOrderByIssueSequenceAsc(
						EVENT_ID, 20, PageRequest.of(0, 201)))
				.willReturn(List.of(first, second));

		var result = couponIssuanceLogService.findLogs(EVENT_ID, 20, 200);

		assertThat(result.eventId()).isEqualTo(EVENT_ID);
		assertThat(result.nextSequence()).isEqualTo(22);
		assertThat(result.hasMore()).isFalse();
		assertThat(result.logs()).extracting(log -> log.issueSequence())
				.containsExactly(21, 22);
		assertThat(result.logs().getFirst().userId()).isEqualTo(101L);
		assertThat(result.logs().getFirst().confirmedAt().toString())
				.isEqualTo("2026-08-27T10:00:01+09:00");
	}

	@Test
	@DisplayName("요청 크기보다 한 건 더 조회해 다음 페이지 존재 여부를 표시한다")
	void indicatesMoreLogsWithoutAdvancingPastVisibleItem() {
		given(couponEventRepository.existsById(EVENT_ID)).willReturn(true);
		given(couponIssueRepository
				.findByCouponEvent_IdAndIssueSequenceGreaterThanOrderByIssueSequenceAsc(
						EVENT_ID, 0, PageRequest.of(0, 3)))
				.willReturn(List.of(
						issue(1L, 1, "2026-08-27T10:00:00", "2026-08-27T10:00:01"),
						issue(2L, 2, "2026-08-27T10:00:00", "2026-08-27T10:00:02"),
						issue(3L, 3, "2026-08-27T10:00:00", "2026-08-27T10:00:03")));

		var result = couponIssuanceLogService.findLogs(EVENT_ID, 0, 2);

		assertThat(result.logs()).hasSize(2);
		assertThat(result.nextSequence()).isEqualTo(2);
		assertThat(result.hasMore()).isTrue();
	}

	@Test
	@DisplayName("존재하지 않는 캠페인은 발급 로그를 조회하지 않고 404 예외로 처리한다")
	void rejectsMissingEvent() {
		given(couponEventRepository.existsById(EVENT_ID)).willReturn(false);

		assertThatThrownBy(() -> couponIssuanceLogService.findLogs(EVENT_ID, 0, 200))
				.isInstanceOf(CouponException.class)
				.satisfies(exception -> assertThat(((CouponException) exception).getErrorCode())
						.isEqualTo(ErrorCode.EVENT_NOT_FOUND));
		verify(couponEventRepository).existsById(EVENT_ID);
	}

	private CouponIssue issue(
			long userId,
			int sequence,
			String issuedAt,
			String confirmedAt) {
		return CouponIssue.builder()
				.user(User.builder().id(userId).build())
				.issueSequence(sequence)
				.issuedAt(LocalDateTime.parse(issuedAt))
				.createdAt(LocalDateTime.parse(confirmedAt))
				.build();
	}
}
