package com.ace.event.consistency;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class ConsistencyStepProgressEvent {
	private final long jobExecutionId;
	private final String checkName;
	private final String checkLabel;
	private final int stepIndex;
	private final int totalSteps;
	private final List<Long> eventIds;
	private final long processedEventCount;
	private final long totalEventCount;
	private final long violationCount;
}
