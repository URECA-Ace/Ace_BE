package com.ace.event.consistency;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.LocalDateTime;

// ALL 스코프 정합성 검증 배치(Spring Batch Job)가 시작될 때 한 번 발행된다. SCHEDULED든
// ON_DEMAND든 트리거 종류와 무관하게 Job이 실제로 시작되는 지점(beforeJob)에서 발행하므로,
// 프론트는 이 이벤트로 "지금 배치가 도는 중"인지 알고 진행 상황 패널을 띄울 수 있다.
@Getter
@Builder
@AllArgsConstructor
@ToString
public class ConsistencyBatchStartedEvent {
	private final long jobExecutionId;
	private final int totalSteps;
	private final String triggerType;
	private final LocalDateTime startedAt;
}
