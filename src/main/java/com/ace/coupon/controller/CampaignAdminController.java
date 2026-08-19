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

// 캠페인 운영용 내부 엔드포인트
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
