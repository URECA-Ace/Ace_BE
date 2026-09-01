package com.ace.event.scheduler;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor
@ToString
public class SchedulerStartedEvent {
	private final String schedulerName;
	private final LocalDateTime startedAt;
}
