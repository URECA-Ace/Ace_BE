package com.ace.consistency.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 정합성 검증의 실행 범위를 표현하는 값 객체.
 *
 * - EVENT 스코프: 특정 coupon_event 하나를 대상으로 검증 (예: 쿠폰 발행이 다 끝났을 때 / 쿠폰 발행 기간이 만료됐을 때 등)
 * - AS_OF_RANGE 스코프: [from, to) 구간에 대해, 그 구간 이전에 "확정"된 데이터만 대상으로 검증
 * - ALL 스코프: 전체 데이터 대상 (온디맨드 재검증, 재현성 확인용)
 *
 * getEventId()/getFrom()/getTo()는 해당 ScopeType이 아닐 때 예외를 던지는 커스텀 검증이 필요하여 Lombok으로 작성하지 않음
 */
@Getter
public final class Scope {

	public enum ScopeType {
		EVENT,
		AS_OF_RANGE,
		ALL
	}

	private final ScopeType type;
	private final Long eventId;          // EVENT 스코프일 때만 값 존재
	private final LocalDateTime from;    // AS_OF_RANGE 스코프일 때만 값 존재
	private final LocalDateTime to;      // AS_OF_RANGE 스코프일 때만 값 존재

	private Scope(ScopeType type, Long eventId, LocalDateTime from, LocalDateTime to) {
		this.type = type;
		this.eventId = eventId;
		this.from = from;
		this.to = to;
	}

	public static Scope ofEvent(Long eventId) {
		Objects.requireNonNull(eventId, "eventId must not be null");
		return new Scope(ScopeType.EVENT, eventId, null, null);
	}

	public static Scope ofAsOfRange(LocalDateTime from, LocalDateTime to) {
		Objects.requireNonNull(from, "from must not be null");
		Objects.requireNonNull(to, "to must not be null");
		if (!from.isBefore(to)) {
			throw new IllegalArgumentException("from must be strictly before to: from=" + from + ", to=" + to);
		}
		return new Scope(ScopeType.AS_OF_RANGE, null, from, to);
	}

	public static Scope all() {
		return new Scope(ScopeType.ALL, null, null, null);
	}

	/** EVENT 스코프가 아닐 때 호출하면 예외를 던진다. */
	public Long getEventId() {
		requireType(ScopeType.EVENT);
		return eventId;
	}

	/** AS_OF_RANGE 스코프가 아닐 때 호출하면 예외를 던진다. */
	public LocalDateTime getFrom() {
		requireType(ScopeType.AS_OF_RANGE);
		return from;
	}

	/** AS_OF_RANGE 스코프가 아닐 때 호출하면 예외를 던진다. */
	public LocalDateTime getTo() {
		requireType(ScopeType.AS_OF_RANGE);
		return to;
	}

	private void requireType(ScopeType expected) {
		if (type != expected) {
			throw new IllegalStateException(
					"This accessor is only available for " + expected + " scope, but was " + type);
		}
	}

	@Override
	public String toString() {
		return switch (type) {
			case EVENT -> "Scope[EVENT, eventId=" + eventId + "]";
			case AS_OF_RANGE -> "Scope[AS_OF_RANGE, from=" + from + ", to=" + to + "]";
			case ALL -> "Scope[ALL]";
		};
	}
}