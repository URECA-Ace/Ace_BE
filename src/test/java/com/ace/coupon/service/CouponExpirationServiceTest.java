package com.ace.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.ace.coupon.entity.CouponIssue;
import com.ace.coupon.enums.CouponIssueStatus;
import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.repository.CouponIssueRepository;

class CouponExpirationServiceTest {

	private CouponIssueRepository couponIssueRepository;
	private CouponExpirationProcessor processor;
	private CouponIssueRedisProperties properties;
	private CouponExpirationService service;

	@BeforeEach
	void setUp() {
		couponIssueRepository = Mockito.mock(CouponIssueRepository.class);
		processor = Mockito.mock(CouponExpirationProcessor.class);
		properties = Mockito.mock(CouponIssueRedisProperties.class);
		given(properties.zoneId()).willReturn(ZoneId.of("Asia/Seoul"));
		service = new CouponExpirationServiceImpl(couponIssueRepository, processor, properties);
	}

	@Test
	@DisplayName("만료 대상 쿠폰이 없으면 0을 반환하고 Processor를 호출하지 않는다")
	void noExpiredCoupons_returnsZero() {
		given(couponIssueRepository.findExpiredIssuesChunk(any(), eq(0L), any()))
				.willReturn(Collections.emptyList());

		int result = service.expireDueCoupons(100);

		assertThat(result).isZero();
		verify(processor, never()).processChunk(any(), any());
	}

	@Test
	@DisplayName("만료 대상 쿠폰이 청크 크기보다 작으면 1회 처리 후 종료된다")
	void singleChunk_processesAndStops() {
		CouponIssue issue1 = CouponIssue.builder()
				.id(1L)
				.status(CouponIssueStatus.ISSUED)
				.validTo(LocalDateTime.now().minusDays(1))
				.build();

		given(couponIssueRepository.findExpiredIssuesChunk(any(), eq(0L), any()))
				.willReturn(List.of(issue1));
		given(processor.processChunk(any(), any())).willReturn(1);

		int result = service.expireDueCoupons(100);

		assertThat(result).isEqualTo(1);
		verify(processor).processChunk(any(), any());
	}

	@Test
	@DisplayName("만료 대상이 여러 청크에 걸쳐 있으면 끝까지 순회하며 모두 처리한다")
	void multiChunk_loopsUntilAllProcessed() {
		CouponIssue issue1 = CouponIssue.builder().id(1L).status(CouponIssueStatus.ISSUED).build();
		CouponIssue issue2 = CouponIssue.builder().id(2L).status(CouponIssueStatus.ISSUED).build();

		given(couponIssueRepository.findExpiredIssuesChunk(any(), eq(0L), any()))
				.willReturn(List.of(issue1));
		given(couponIssueRepository.findExpiredIssuesChunk(any(), eq(1L), any()))
				.willReturn(List.of(issue2));
		given(couponIssueRepository.findExpiredIssuesChunk(any(), eq(2L), any()))
				.willReturn(Collections.emptyList());

		given(processor.processChunk(any(), any())).willReturn(1);

		int result = service.expireDueCoupons(1);

		assertThat(result).isEqualTo(2);
		verify(processor, Mockito.times(2)).processChunk(any(), any());
	}
}
