package com.ace.event.consistency;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.LocalDateTime;

// 배치 안에서 Step(= Check 하나)이 시작될 때 발행된다. ConsistencyStepCompletedEvent(완료)와
// 짝을 이뤄서, 프론트가 "지금 어떤 Check를 실행 중인지"를 실시간으로 표시할 수 있게 해준다.
@Getter
@Builder
@AllArgsConstructor
@ToString
public class ConsistencyStepStartedEvent {
	private final String checkName;
	private final String triggerType;
	private final LocalDateTime startedAt;
}
