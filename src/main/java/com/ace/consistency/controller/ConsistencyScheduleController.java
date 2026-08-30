package com.ace.consistency.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ace.common.ApiResponse;
import com.ace.consistency.dto.request.ConsistencyScheduleUpdateRequest;
import com.ace.consistency.dto.response.ConsistencyScheduleResponse;
import com.ace.consistency.service.ConsistencyScheduleService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 관리 화면에서 정합성 스케줄러들의 실행 주기를 조회/변경하는 API. */
@RestController
@RequestMapping("/api/v1/consistency")
@RequiredArgsConstructor
@Validated
public class ConsistencyScheduleController {

	private final ConsistencyScheduleService scheduleService;

	@GetMapping("/schedules")
	public ResponseEntity<ApiResponse<List<ConsistencyScheduleResponse>>> findAll() {
		return ResponseEntity.ok(ApiResponse.success(scheduleService.findAll()));
	}

	@PatchMapping("/schedules/{schedulerName}")
	public ResponseEntity<ApiResponse<ConsistencyScheduleResponse>> updateInterval(
			@PathVariable(name = "schedulerName") String schedulerName,
			@Valid @RequestBody ConsistencyScheduleUpdateRequest request) {
		return ResponseEntity.ok(ApiResponse.success(scheduleService.changeInterval(schedulerName, request.intervalMs())));
	}
}
