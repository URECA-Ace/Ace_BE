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
import com.ace.coupon.enums.CouponIssueStatus;
import com.ace.coupon.repository.CouponEventRepository;
import com.ace.coupon.repository.CouponIssueRepository;
import com.ace.user.entity.User;
import com.ace.user.repository.UserRepository;

class CouponIssuanceLogServiceTest {

	private static final long EVENT_ID = 60L;
	private static final ZoneId ZONE_ID = ZoneId.of("Asia/Seoul");

	private CouponEventRepository couponEventRepository;
	private CouponIssueRepository couponIssueRepository;
	private RedisPendingIssuanceLogReader pendingLogReader;
	private UserRepository userRepository;
	private CouponIssuanceLogService couponIssuanceLogService;

	@BeforeEach
	void setUp() {
		couponEventRepository = Mockito.mock(CouponEventRepository.class);
		couponIssueRepository = Mockito.mock(CouponIssueRepository.class);
		pendingLogReader = Mockito.mock(RedisPendingIssuanceLogReader.class);
		userRepository = Mockito.mock(UserRepository.class);
		couponIssuanceLogService = new CouponIssuanceLogService(
				couponEventRepository,
				couponIssueRepository,
				pendingLogReader,
				userRepository,
				Clock.fixed(Instant.parse("2026-08-27T01:00:00Z"), ZONE_ID));
	}

	@Test
	@DisplayName("DB에 확정된 발급 로그를 순번 커서 다음부터 개인정보를 마스킹해 반환한다")
	void findsConfirmedLogsAfterSequence() {
		CouponIssue first = issue(
				101L, "홍길동", "honggildong@example.com", "010-1234-5678",
				21, "2026-08-27T10:00:00", "2026-08-27T10:00:01");
		CouponIssue second = issue(
				102L, "김철", "kim@example.com", "01098765432",
				22, "2026-08-27T10:00:00", "2026-08-27T10:00:02");
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
		assertThat(result.logs().getFirst()).satisfies(log -> {
			assertThat(log.userId()).isEqualTo(101L);
			assertThat(log.maskedUserName()).isEqualTo("홍*동");
			assertThat(log.maskedUserEmail()).isEqualTo("hon****@example.com");
			assertThat(log.maskedUserPhone()).isEqualTo("010-****-5678");
			assertThat(log.status()).isEqualTo("ISSUED");
		});
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
						issue(1L, "사용자일", "user1@example.com", "010-0000-0001",
								1, "2026-08-27T10:00:00", "2026-08-27T10:00:01"),
						issue(2L, "사용자이", "user2@example.com", "010-0000-0002",
								2, "2026-08-27T10:00:00", "2026-08-27T10:00:02"),
						issue(3L, "사용자삼", "user3@example.com", "010-0000-0003",
								3, "2026-08-27T10:00:00", "2026-08-27T10:00:03")));

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
			String name,
			String email,
			String phone,
			int sequence,
			String issuedAt,
			String confirmedAt) {
		return CouponIssue.builder()
				.user(User.builder()
						.id(userId)
						.name(name)
						.email(email)
						.phone(phone)
						.build())
				.issueSequence(sequence)
				.status(CouponIssueStatus.ISSUED)
				.issuedAt(LocalDateTime.parse(issuedAt))
				.createdAt(LocalDateTime.parse(confirmedAt))
				.build();
	}
}
