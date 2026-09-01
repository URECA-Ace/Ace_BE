package com.ace.event.consistency;

import com.ace.consistency.common.Scope;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Getter
@Builder
@AllArgsConstructor
@ToString
public class ConsistencyCheckFailedEvent {
	private final String checkName;
	private final String triggerType;
	private final String scopeDescription;
	private final long violationCount;
	private final Map<String, Object> diffDetail;
	private final LocalDateTime detectedAt;
}