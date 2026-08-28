package com.ace.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageRequest;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.entity.CouponIssue;
import com.ace.coupon.enums.CouponIssueStatus;
import com.ace.coupon.persistence.IssueRecord;
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
		given(couponIssueRepository
				.findByCouponEvent_IdAndIssueSequenceGreaterThanOrderByIssueSequenceAsc(
						EVENT_ID, 20, PageRequest.of(0, 201)))
				.willReturn(List.of(first, second));

		var result = couponIssuanceLogService.findLogs(EVENT_ID, 20, 200);

		assertThat(result.eventId()).isEqualTo(EVENT_ID);
		assertThat(result.nextSequence()).isEqualTo(22L);
		assertThat(result.hasMore()).isFalse();
		assertThat(result.logs()).extracting(log -> log.issueSequence())
				.containsExactly(21L, 22L);
		assertThat(result.logs().getFirst()).satisfies(log -> {
			assertThat(log.userId()).isEqualTo(101L);
			assertThat(log.maskedUserName()).isEqualTo("홍*동");
			assertThat(log.maskedUserEmail()).isEqualTo("hon****@example.com");
			assertThat(log.maskedUserPhone()).isEqualTo("010-****-5678");
			assertThat(log.status()).isEqualTo("ISSUED");
		});
		assertThat(result.logs().getFirst().persistedAt().toString())
				.isEqualTo("2026-08-27T10:00:01+09:00");
		verify(couponEventRepository, never()).existsById(EVENT_ID);
	}

	@Test
	@DisplayName("Redis 저장 대기는 처리 중으로 보이고 같은 순번의 DB 확정 행이 생기면 발급 완료가 우선한다")
	void transitionsProcessingLogToIssuedWhenDatabaseRowExists() {
		IssueRecord processing = issueRecord(101L, 21);
		IssueRecord confirmed = issueRecord(102L, 22);
		CouponIssue confirmedIssue = issue(
				102L, "김철수", "kim@example.com", "010-9876-5432",
				22, "2026-08-27T10:00:00", "2026-08-27T10:00:02");
		User processingUser = User.builder()
				.id(101L)
				.name("홍길동")
				.email("honggildong@example.com")
				.phone("010-1234-5678")
				.build();
		given(couponIssueRepository
				.findByCouponEvent_IdAndIssueSequenceGreaterThanOrderByIssueSequenceAsc(
						EVENT_ID, 20, PageRequest.of(0, 201)))
				.willReturn(List.of(confirmedIssue));
		given(pendingLogReader.findAfter(EVENT_ID, 20, 201))
				.willReturn(List.of(processing, confirmed));
		given(userRepository.findAllById(Mockito.any()))
				.willReturn(List.of(processingUser));

		var result = couponIssuanceLogService.findLogs(EVENT_ID, 20, 200);

		assertThat(result.logs()).extracting(log -> log.issueSequence())
				.containsExactly(21L, 22L);
		assertThat(result.logs().getFirst()).satisfies(log -> {
			assertThat(log.status()).isEqualTo("PROCESSING");
			assertThat(log.maskedUserName()).isEqualTo("홍*동");
			assertThat(log.maskedUserEmail()).isEqualTo("hon****@example.com");
			assertThat(log.maskedUserPhone()).isEqualTo("010-****-5678");
			assertThat(log.persistedAt()).isNull();
		});
		assertThat(result.logs().getLast().status()).isEqualTo("ISSUED");
		assertThat(result.logs().getLast().maskedUserName()).isEqualTo("김*수");
	}

	@Test
	@DisplayName("요청 크기보다 한 건 더 조회해 다음 페이지 존재 여부를 표시한다")
	void indicatesMoreLogsWithoutAdvancingPastVisibleItem() {
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
		assertThat(result.nextSequence()).isEqualTo(2L);
		assertThat(result.hasMore()).isTrue();
	}

	@Test
	@DisplayName("Redis와 DB의 같은 순번이 병합되면 보이지 않는 중복 때문에 다음 페이지를 만들지 않는다")
	void doesNotReportMoreForOverwrittenDuplicate() {
		IssueRecord processing = issueRecord(101L, 21);
		CouponIssue confirmedIssue = issue(
				101L, "홍길동", "hong@example.com", "010-1234-5678",
				21, "2026-08-27T10:00:00", "2026-08-27T10:00:01");
		given(pendingLogReader.findAfter(EVENT_ID, 20, 2))
				.willReturn(List.of(processing));
		given(couponIssueRepository
				.findByCouponEvent_IdAndIssueSequenceGreaterThanOrderByIssueSequenceAsc(
						EVENT_ID, 20, PageRequest.of(0, 2)))
				.willReturn(List.of(confirmedIssue));

		var result = couponIssuanceLogService.findLogs(EVENT_ID, 20, 1);

		assertThat(result.logs()).hasSize(1);
		assertThat(result.logs().getFirst().status()).isEqualTo("ISSUED");
		assertThat(result.hasMore()).isFalse();
	}

	@Test
	@DisplayName("int 범위를 넘는 Redis 발급 순번도 관제 응답에서 유실하지 않는다")
	void keepsLongRedisSequence() {
		long sequence = (long) Integer.MAX_VALUE + 1;
		given(pendingLogReader.findAfter(EVENT_ID, Integer.MAX_VALUE, 2))
				.willReturn(List.of(issueRecord(101L, sequence)));

		var result = couponIssuanceLogService.findLogs(EVENT_ID, Integer.MAX_VALUE, 1);

		assertThat(result.logs()).extracting(log -> log.issueSequence())
				.containsExactly(sequence);
		assertThat(result.nextSequence()).isEqualTo(sequence);
	}

	@Test
	@DisplayName("로그가 비어 있고 캠페인도 존재하지 않으면 404 예외로 처리한다")
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
			String persistedAt) {
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
				.createdAt(LocalDateTime.parse(persistedAt))
				.build();
	}

	private IssueRecord issueRecord(long userId, long sequence) {
		return new IssueRecord(
				UUID.randomUUID(),
				EVENT_ID,
				userId,
				0,
				userId - 1,
				sequence,
				Instant.parse("2026-08-27T01:00:00Z"),
				UUID.randomUUID().toString());
	}
}
