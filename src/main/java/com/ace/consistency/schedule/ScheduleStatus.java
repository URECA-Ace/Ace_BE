package com.ace.consistency.schedule;

import java.time.Instant;

/** 스케줄러 하나의 현재 주기/다음 실행 예정 시각/진행 여부를 담는 값 객체. */
public record ScheduleStatus(String schedulerName, long intervalMs, Instant nextRunAt, boolean running) {
}
