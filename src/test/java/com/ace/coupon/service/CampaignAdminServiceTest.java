package com.ace.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.dto.response.CampaignInitializationResponse;
import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.redis.CampaignInitializationResult;
import com.ace.coupon.redis.CampaignRedisInitializer;
import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.repository.CouponEventRepository;

@ExtendWith(MockitoExtension.class)
class CampaignAdminServiceTest {

	private static final LocalDateTime OPEN_AT = LocalDateTime.of(2026, 8, 19, 12, 0);

	@Mock
	private CouponEventRepository couponEventRepository;

	@Mock
	private CampaignRedisInitializer campaignRedisInitializer;

	private CampaignAdminService service;

	@BeforeEach
	void setUp() {
		service = new CampaignAdminService(
				couponEventRepository,
				campaignRedisInitializer,
				new CouponIssueRedisProperties(Duration.ofDays(7), ZoneId.of("Asia/Seoul")));
	}

	private CouponEvent event() {
		return CouponEvent.builder()
				.id(1L)
				.round(1)
				.openAt(OPEN_AT)
				.closeAt(OPEN_AT.plusHours(1))
				.totalStock(10_000)
				.remainingStock(10_000)
				.issuedQuantity(0)
				.perUserLimit(1)
				.createdAt(OPEN_AT)
				.updatedAt(OPEN_AT)
				.build();
	}

	private void givenResult(CampaignInitializationResult result) {
		given(couponEventRepository.findById(1L)).willReturn(Optional.of(event()));
		given(campaignRedisInitializer.initialize(any(CouponEvent.class))).willReturn(result);
	}

	@Test
	@DisplayName("MySQL 회차 값을 그대로 Redis 에 넣는다")
	void initializesFromEventRow() {
		givenResult(CampaignInitializationResult.INITIALIZED);

		CampaignInitializationResponse response = service.initialize(1L);

		ArgumentCaptor<CouponEvent> captor = ArgumentCaptor.forClass(CouponEvent.class);
		verify(campaignRedisInitializer).initialize(captor.capture());
		assertThat(captor.getValue().getId()).isEqualTo(1L);
		assertThat(captor.getValue().getTotalStock()).isEqualTo(10_000);

		assertThat(response.result()).isEqualTo(CampaignInitializationResult.INITIALIZED);
		assertThat(response.totalStock()).isEqualTo(10_000);
		assertThat(response.openAt().toLocalDateTime()).isEqualTo(OPEN_AT);
		assertThat(response.closeAt().toLocalDateTime()).isEqualTo(OPEN_AT.plusHours(1));
	}

	@Test
	@DisplayName("이미 같은 설정으로 초기화됐으면 성공으로 본다")
	void treatsAlreadyInitializedAsSuccess() {
		givenResult(CampaignInitializationResult.ALREADY_INITIALIZED);

		assertThat(service.initialize(1L).result())
				.isEqualTo(CampaignInitializationResult.ALREADY_INITIALIZED);
	}

	@Test
	@DisplayName("없는 회차는 404")
	void rejectsMissingEvent() {
		given(couponEventRepository.findById(99L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.initialize(99L))
				.isInstanceOfSatisfying(CouponException.class,
						exception -> assertThat(exception.getErrorCode())
								.isEqualTo(ErrorCode.EVENT_NOT_FOUND));

		verify(campaignRedisInitializer, never()).initialize(any(CouponEvent.class));
	}

	@Test
	@DisplayName("다른 설정으로 이미 초기화됐으면 409")
	void rejectsConfigurationConflict() {
		givenResult(CampaignInitializationResult.CONFIGURATION_CONFLICT);

		assertThatThrownBy(() -> service.initialize(1L))
				.isInstanceOfSatisfying(CouponException.class, exception -> {
					assertThat(exception.getErrorCode())
							.isEqualTo(ErrorCode.CAMPAIGN_CONFIG_CONFLICT);
					assertThat(exception.getMessage()).contains("키를 지우고");
				});
	}

	@Test
	@DisplayName("마감된 회차는 400 으로 거절하고 사유를 알려준다")
	void reportsInvalidConfiguration() {
		givenResult(CampaignInitializationResult.INVALID_CONFIGURATION);

		assertThatThrownBy(() -> service.initialize(1L))
				.isInstanceOfSatisfying(CouponException.class, exception -> {
					assertThat(exception.getErrorCode())
							.isEqualTo(ErrorCode.CAMPAIGN_NOT_INITIALIZABLE);
					assertThat(exception.getMessage())
							.contains("보존기간이 지났거나")
							.contains("totalStock=10000");
				});
	}

	@Test
	@DisplayName("Redis 쓰기 실패는 500")
	void reportsWriteError() {
		givenResult(CampaignInitializationResult.INTERNAL_WRITE_ERROR);

		assertThatThrownBy(() -> service.initialize(1L))
				.isInstanceOfSatisfying(CouponException.class,
						exception -> assertThat(exception.getErrorCode())
								.isEqualTo(ErrorCode.CAMPAIGN_INIT_FAILED));
	}
}
