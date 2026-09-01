package com.ace.event.scheduler;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.Map;

@Getter
@Builder
@AllArgsConstructor
@ToString
public class SchedulerCompletedEvent {
	private final String schedulerName;
	private final Map<String, Object> result;
	private final LocalDateTime completedAt;
}
