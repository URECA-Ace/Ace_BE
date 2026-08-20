package com.ace.consistency.common;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 정합성 검증의 실행 범위를 표현하는 값 객체.
 *
 * - EVENT 스코프: 특정 coupon_event 하나를 대상으로 검증 (예: 이벤트 종료 트리거)
 * - AS_OF_RANGE 스코프: [from, to) 구간에 대해, 그 구간 이전에 "확정"된 데이터만 대상으로 검증
 *   (파이프라인 lag을 고려해 실시간 데이터는 검증 대상에서 제외하기 위함)
 * - ALL 스코프: 전체 데이터 대상 (온디맨드 재검증, 재현성 확인용)
 *
 * getEventId()/getFrom()/getTo()는 해당 ScopeType이 아닐 때 예외를 던지는 커스텀 검증이
 * 필요해서 Lombok @Getter만 필드 일부(type)에 붙이고 나머지는 직접 구현했다.
 *
 * 이 엄격한 getter들은 Check 내부 로직(항상 scope.getType()을 먼저 확인하고 호출)에서만
 * 쓰기 위한 것이라 그대로 두되, Jackson이 JSON 직렬화 시 무조건 모든 getXxx()를 호출하면서
 * 잘못된 타입에서 예외가 터지는 문제가 있었다. 그래서 Jackson 직렬화 전용으로 null-safe한
 * getter를 별도로 두고, 기존 엄격한 getter는 @JsonIgnore로 직렬화 대상에서 뺐다.
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
	private final List<Long> eventIds;   // ALL 스코프일 때만 값 존재
	private final LocalDateTime from;    // AS_OF_RANGE 스코프일 때만 값 존재
	private final LocalDateTime to;      // AS_OF_RANGE 스코프 및 ALL 스코프일때 사용

	private Scope(ScopeType type, Long eventId, List<Long> eventIds, LocalDateTime from, LocalDateTime to) {
		this.type = type;
		this.eventId = eventId;
		this.eventIds = eventIds;
		this.from = from;
		this.to = to;
	}

	public static Scope ofEvent(Long eventId) {
		Objects.requireNonNull(eventId, "eventId must not be null");
		return new Scope(ScopeType.EVENT, eventId, null, null, null);
	}

	public static Scope ofAsOfRange(LocalDateTime from, LocalDateTime to) {
		Objects.requireNonNull(from, "from must not be null");
		Objects.requireNonNull(to, "to must not be null");
		if (!from.isBefore(to)) {
			throw new IllegalArgumentException("from must be strictly before to: from=" + from + ", to=" + to);
		}
		return new Scope(ScopeType.AS_OF_RANGE, null, null, from, to);
	}

	public static Scope all(List<Long> eventIds, LocalDateTime to) {
		Objects.requireNonNull(eventIds, "eventIds must not be null");
		Objects.requireNonNull(to, "to must not be null");
		return new Scope(ScopeType.ALL, null, eventIds, null, to);
	}

	public static Scope all(LocalDateTime to) {
		Objects.requireNonNull(to, "to must not be null");
		return new Scope(ScopeType.ALL, null, null, null, to);
	}

	/** [내부 로직 전용] EVENT 스코프가 아닐 때 호출하면 예외를 던진다. JSON 직렬화 대상 아님. */
	@JsonIgnore
	public Long getEventId() {
		requireType(ScopeType.EVENT);
		return eventId;
	}

	/** [내부 로직 전용] AS_OF_RANGE 스코프가 아닐 때 호출하면 예외를 던진다. JSON 직렬화 대상 아님. */
	@JsonIgnore
	public LocalDateTime getFrom() {
		requireType(ScopeType.AS_OF_RANGE);
		return from;
	}

	/** [내부 로직 전용] AS_OF_RANGE 또는 ALL 스코프가 아닐 때 호출하면 예외를 던진다. JSON 직렬화 대상 아님. */
	@JsonIgnore
	public LocalDateTime getTo() {
		if (type != ScopeType.AS_OF_RANGE && type != ScopeType.ALL) {
			throw new IllegalStateException("This accessor is only available for AS_OF_RANGE or ALL scope, but was " + type);
		}
		return to;
	}

	/** [JSON 직렬화 전용] EVENT 스코프가 아니면 예외 대신 null을 반환한다. */
	@JsonProperty("eventId")
	public Long getEventIdOrNull() {
		return type == ScopeType.EVENT ? eventId : null;
	}

	/** [JSON 직렬화 전용] AS_OF_RANGE 스코프가 아니면 예외 대신 null을 반환한다. */
	@JsonProperty("from")
	public LocalDateTime getFromOrNull() {
		return type == ScopeType.AS_OF_RANGE ? from : null;
	}

	/** [JSON 직렬화 전용] AS_OF_RANGE 스코프가 아니면 예외 대신 null을 반환한다. */
	@JsonProperty("to")
	public LocalDateTime getToOrNull() {
		return (type == ScopeType.AS_OF_RANGE || type == ScopeType.ALL) ? to : null;
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