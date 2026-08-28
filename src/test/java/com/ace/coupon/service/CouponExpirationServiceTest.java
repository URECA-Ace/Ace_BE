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

	@Test
	@DisplayName("대상이 정확히 chunkSize 배수일 때 다음 루프에서 빈 리스트를 받고 종료된다")
	void exactChunkSizeMultiplier_loopsProperly() {
		CouponIssue issue1 = CouponIssue.builder().id(1L).status(CouponIssueStatus.ISSUED).build();
		CouponIssue issue2 = CouponIssue.builder().id(2L).status(CouponIssueStatus.ISSUED).build();

		given(couponIssueRepository.findExpiredIssuesChunk(any(), eq(0L), any()))
				.willReturn(List.of(issue1, issue2)); // chunkSize와 동일한 개수 반환
		given(couponIssueRepository.findExpiredIssuesChunk(any(), eq(2L), any()))
				.willReturn(Collections.emptyList()); // 다음 루프에서 빈 리스트

		given(processor.processChunk(any(), any())).willReturn(2);

		int result = service.expireDueCoupons(2);

		assertThat(result).isEqualTo(2);
		verify(couponIssueRepository, Mockito.times(2)).findExpiredIssuesChunk(any(), any(), any());
		verify(processor, Mockito.times(1)).processChunk(any(), any());
	}

	@Test
	@DisplayName("동시성 등으로 이미 EXPIRED 상태로 전이되어 갱신 건수가 0건이면 0을 반환하여 멱등성을 보장한다")
	void idempotency_alreadyExpired() {
		CouponIssue issue1 = CouponIssue.builder().id(1L).status(CouponIssueStatus.ISSUED).build();

		given(couponIssueRepository.findExpiredIssuesChunk(any(), eq(0L), any()))
				.willReturn(List.of(issue1));
		// 벌크 쿼리가 0을 반환하여 processor가 0을 반환한다고 가정
		given(processor.processChunk(any(), any())).willReturn(0);

		int result = service.expireDueCoupons(100);

		assertThat(result).isZero();
		verify(processor).processChunk(any(), any());
	}
}
