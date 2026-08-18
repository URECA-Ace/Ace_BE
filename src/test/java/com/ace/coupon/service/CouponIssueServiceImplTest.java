package com.ace.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.dto.response.CouponIssueAcceptedResponse;
import com.ace.coupon.dto.response.CouponIssueStatusResponse;
import com.ace.coupon.enums.IssueRequestStatus;
import com.ace.coupon.redis.CouponIssueDecision;
import com.ace.coupon.redis.CouponIssueLuaCode;
import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.redis.CouponIssueRequestState;
import com.ace.coupon.redis.RedisCouponIssueProcessor;

class CouponIssueServiceImplTest {

	private RedisCouponIssueProcessor processor;
	private CouponIssueService service;

	@BeforeEach
	void setUp() {
		processor = Mockito.mock(RedisCouponIssueProcessor.class);
		service = new CouponIssueServiceImpl(
				processor,
				new CouponIssueRedisProperties(Duration.ofDays(7), ZoneId.of("Asia/Seoul")));
	}

	@Test
	@DisplayName("Lua 승인 결과를 202 응답 데이터로 변환한다")
	void returnsAcceptedResponse() {
		UUID requestId = UUID.randomUUID();
		Instant decidedAt = Instant.parse("2026-08-14T06:00:00Z");
		given(processor.issue(1L, 2L, requestId))
				.willReturn(new CouponIssueDecision(CouponIssueLuaCode.ACCEPTED, 7L, 93L, decidedAt));

		CouponIssueAcceptedResponse response = service.issue(1L, 2L, requestId);

		assertThat(response.requestId()).isEqualTo(requestId);
		assertThat(response.issueSequence()).isEqualTo(7L);
		assertThat(response.remainingStock()).isEqualTo(93L);
		assertThat(response.status()).isEqualTo(IssueRequestStatus.ACCEPTED);
		assertThat(response.acceptedAt().toInstant()).isEqualTo(decidedAt);
	}

	@Test
	@DisplayName("Lua 재고 소진 결과를 명확한 도메인 오류로 변환한다")
	void mapsSoldOut() {
		UUID requestId = UUID.randomUUID();
		given(processor.issue(1L, 2L, requestId))
				.willReturn(new CouponIssueDecision(
						CouponIssueLuaCode.SOLD_OUT, null, 0L, Instant.now()));

		assertThatThrownBy(() -> service.issue(1L, 2L, requestId))
				.isInstanceOfSatisfying(CouponException.class,
						exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SOLD_OUT));
	}

	@Test
	@DisplayName("requestId 상태를 Redis 판정 상태로 조회한다")
	void findsRequestStatus() {
		UUID requestId = UUID.randomUUID();
		Instant decidedAt = Instant.parse("2026-08-14T06:00:00Z");
		given(processor.findRequest(1L, requestId)).willReturn(new CouponIssueRequestState(
				requestId, 1L, 2L, IssueRequestStatus.ACCEPTED, 7L, 93L, decidedAt));

		CouponIssueStatusResponse response = service.findStatus(1L, requestId);

		assertThat(response.status()).isEqualTo(IssueRequestStatus.ACCEPTED);
		assertThat(response.decidedAt().toInstant()).isEqualTo(decidedAt);
	}

	@Test
	@DisplayName("존재하지 않는 requestId는 조회 오류로 변환한다")
	void rejectsMissingRequestStatus() {
		UUID requestId = UUID.randomUUID();
		given(processor.findRequest(1L, requestId)).willReturn(null);

		assertThatThrownBy(() -> service.findStatus(1L, requestId))
				.isInstanceOfSatisfying(CouponException.class,
						exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ISSUE_NOT_FOUND));
	}
}
