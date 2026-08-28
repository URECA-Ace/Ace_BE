package com.ace.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.entity.CouponHistory;
import com.ace.coupon.entity.CouponIssue;
import com.ace.coupon.enums.CouponIssueStatus;
import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.repository.CouponHistoryRepository;
import com.ace.coupon.repository.CouponIssueRepository;
import com.ace.coupon.repository.CouponStateIdempotencyRepository;
import com.ace.user.entity.User;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class CouponStateProcessorTest {

	private CouponIssueRepository couponIssueRepository;
	private CouponHistoryRepository couponHistoryRepository;
	private CouponStateProcessor processor;

	@BeforeEach
	void setUp() {
		couponIssueRepository = mock(CouponIssueRepository.class);
		couponHistoryRepository = mock(CouponHistoryRepository.class);
		CouponStateIdempotencyRepository idempotencyRepository =
				mock(CouponStateIdempotencyRepository.class);
		CouponIssueRedisProperties properties = mock(CouponIssueRedisProperties.class);
		given(properties.zoneId()).willReturn(ZoneId.of("Asia/Seoul"));
		processor = new CouponStateProcessor(
				couponIssueRepository, couponHistoryRepository, idempotencyRepository, properties,
				new SimpleMeterRegistry());
	}

	@Test
	@DisplayName("수동 만료 이력은 요청 사유와 무관하게 MANUAL_EXPIRED로 기록한다")
	void manualExpire_recordsStableReason() {
		long issueId = 1L;
		long userId = 100L;
		long eventId = 10L;
		CouponIssue issue = mock(CouponIssue.class);
		User user = mock(User.class);
		CouponEvent event = mock(CouponEvent.class);
		given(issue.getUser()).willReturn(user);
		given(user.getId()).willReturn(userId);
		given(issue.getStatus()).willReturn(CouponIssueStatus.ISSUED);
		given(issue.getId()).willReturn(issueId);
		given(issue.getCouponEvent()).willReturn(event);
		given(event.getId()).willReturn(eventId);
		given(couponIssueRepository.findByIdForUpdate(issueId)).willReturn(Optional.of(issue));

		processor.processStateChange(
				issueId, userId, UUID.randomUUID(), CouponIssueStatus.EXPIRED, "임의 요청 사유");

		ArgumentCaptor<CouponHistory> historyCaptor = ArgumentCaptor.forClass(CouponHistory.class);
		verify(couponHistoryRepository).save(historyCaptor.capture());
		assertThat(historyCaptor.getValue().getReason()).isEqualTo("MANUAL_EXPIRED");
		assertThat(historyCaptor.getValue().getFromStatus()).isEqualTo(CouponIssueStatus.ISSUED);
		assertThat(historyCaptor.getValue().getToStatus()).isEqualTo(CouponIssueStatus.EXPIRED);
		verify(issue).expire(any(LocalDateTime.class));
	}
}
