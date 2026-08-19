package com.ace.coupon.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ace.common.ApiResponse;
import com.ace.coupon.dto.response.CampaignInitializationResponse;
import com.ace.coupon.service.CampaignAdminService;

import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

/**
 * 캠페인 운영용 내부 엔드포인트.
 *
 * <p>Redis 상태를 바꾸므로 <b>기본적으로 노출되지 않는다.</b>
 * {@code coupon.issue.admin.enabled=true} 를 명시해야 빈이 만들어진다.
 * 로컬 개발과 부하테스트에서 회차를 반복 준비하는 용도다.
 *
 * <p>인증은 아직 없다. 프로젝트에 security 의존성이 없어서, 운영 노출 방식은
 * 팀에서 초기화 주체가 정해질 때 함께 정한다. 그때까지 이 플래그가 유일한 방어선이다.
 */
@RestController
@RequestMapping("/internal/campaigns")
@ConditionalOnProperty(prefix = "coupon.issue.admin", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Validated
public class CampaignAdminController {

	private final CampaignAdminService campaignAdminService;

	@PostMapping("/{eventId}/init")
	public ResponseEntity<ApiResponse<CampaignInitializationResponse>> initialize(
			@PathVariable(name = "eventId")
			@Positive(message = "eventId는 0보다 커야 합니다.")
			Long eventId) {

		return ResponseEntity.ok(ApiResponse.success(campaignAdminService.initialize(eventId)));
	}
}
