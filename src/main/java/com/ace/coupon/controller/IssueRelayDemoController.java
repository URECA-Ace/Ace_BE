package com.ace.coupon.controller;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ace.common.ApiResponse;
import com.ace.coupon.persistence.relay.IssueStreamRelay;

/**
 * 발급 Stream 릴레이(IssueStreamRelay)를 시연/운영 확인 목적으로 강제 정지·재시작하는 내부 API.
 *
 * RedisMysqlLossConsistencyCheck의 "릴레이 컨슈머 정지" 위반과 RESTART_RELAY_CONSUMER 자동
 * 복구는 기존 위반 주입기(Redis 재고만 직접 건드리는 방식)로는 재현할 수 없다 — 그 주입기는
 * 릴레이를 그대로 살려두기 때문이다. 이 컨트롤러가 릴레이 자체를 실제로 멈춰야만 그 복구 경로를
 * 눈으로 확인할 수 있다.
 *
 * 운영 트래픽에 직접 영향을 주는 위험한 작업이므로 기본적으로 비활성화되어 있고,
 * coupon.issue.relay.demo-control.enabled=true로 명시적으로 켜야 노출된다.
 */
@RestController
@RequestMapping({"/internal/coupon/relay", "/api/v1/coupon/relay"})
@ConditionalOnProperty(prefix = "coupon.issue.relay.demo-control", name = "enabled", havingValue = "true")
public class IssueRelayDemoController {

	private final ObjectProvider<IssueStreamRelay> relayProvider;

	public IssueRelayDemoController(ObjectProvider<IssueStreamRelay> relayProvider) {
		this.relayProvider = relayProvider;
	}

	/** 릴레이 빈 존재 여부와 현재 동작 상태를 조회한다. */
	@GetMapping("/status")
	public ResponseEntity<ApiResponse<RelayStatusResponse>> status() {
		return ResponseEntity.ok(ApiResponse.success(currentStatus()));
	}

	/** 릴레이를 강제로 정지시킨다 (RedisMysqlLossConsistencyCheck 위반 재현용). */
	@PostMapping("/stop")
	public ResponseEntity<ApiResponse<RelayStatusResponse>> stop() {
		IssueStreamRelay relay = relayProvider.getIfAvailable();
		if (relay != null) {
			relay.stop();
		}
		return ResponseEntity.ok(ApiResponse.success(currentStatus()));
	}

	/** 릴레이를 다시 시작한다 (RESTART_RELAY_CONSUMER와 동일한 호출, 복구 버튼을 거치지 않고 직접 확인용). */
	@PostMapping("/start")
	public ResponseEntity<ApiResponse<RelayStatusResponse>> start() {
		IssueStreamRelay relay = relayProvider.getIfAvailable();
		if (relay != null) {
			relay.start();
		}
		return ResponseEntity.ok(ApiResponse.success(currentStatus()));
	}

	private RelayStatusResponse currentStatus() {
		IssueStreamRelay relay = relayProvider.getIfAvailable();
		return new RelayStatusResponse(relay != null, relay != null && relay.isRunning());
	}

	public record RelayStatusResponse(boolean available, boolean running) {
	}
}
