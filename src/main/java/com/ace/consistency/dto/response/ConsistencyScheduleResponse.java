package com.ace.consistency.dto.response;

import com.ace.consistency.schedule.ScheduleStatus;

/** 프론트에서 다음 실행까지 남은 시간과 진행중 여부를 표시할 수 있도록 스케줄러 상태를 내려준다. */
public record ConsistencyScheduleResponse(String schedulerName, long intervalMs, long nextRunAtEpochMs, boolean running) {

	public static ConsistencyScheduleResponse of(ScheduleStatus status, boolean running) {
		return new ConsistencyScheduleResponse(
				status.schedulerName(), status.intervalMs(), status.nextRunAt().toEpochMilli(), running);
	}
}
