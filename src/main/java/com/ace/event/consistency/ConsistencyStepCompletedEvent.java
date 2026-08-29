package com.ace.event.consistency;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor
@ToString
public class ConsistencyStepCompletedEvent {
	private final String checkName;
	private final String triggerType;
	private final String status;
	private final int violationCount;
	private final LocalDateTime completedAt;
}
