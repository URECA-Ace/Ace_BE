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
public class ConsistencyBatchCompletedEvent {
	private final long jobExecutionId;
	private final String status;
	private final int stepCount;
	private final LocalDateTime completedAt;
}
