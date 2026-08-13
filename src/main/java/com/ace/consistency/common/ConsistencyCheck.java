package com.ace.consistency.common;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * 모든 정합성 검증 항목(재고 정합성, 초과 발급, 1인 1매, 상태 전이,
 * 파이프라인 간 정합성, 멱등성, DLQ 완결성 등)이 구현하는 공통 인터페이스.
 *
 * 구현체는 다음 원칙을 지켜야 한다:
 *  - 읽기 전용이어야 한다. 데이터 변경을 발생시키지 않는다.
 *  - 같은 scope로 여러 번 호출해도 항상 같은 결과를 반환해야 한다.
 *  - 지원하지 않는 Scope.ScopeType이 들어오면 UnsupportedScopeException을 던진다.
 *  - 예외는 삼키지 않고 그대로 던진다 (Runner가 잡아서 ERROR 상태로 변환한다).
 */
public interface ConsistencyCheck {

	/**
	 * 이 Check의 식별 이름. VerificationResult.checkName, 로그, 대시보드 필터링에 사용된다.
	 * 기본적으로 클래스의 simple name을 그대로 사용한다 (예: "StockConsistencyCheck").
	 * 클래스명과 다른 이름을 쓰고 싶은 특수한 경우에만 오버라이드하면 된다.
	 */
	default String getName() {
		return this.getClass().getSimpleName();
	}

	/**
	 * 이 Check가 지원하는 Scope 종류들. Runner가 실행 전 사전 검증에 사용한다.
	 * 하나의 Check가 여러 ScopeType을 지원할 수 있다 — 예를 들어 재고 정합성 검사는
	 * "특정 이벤트 하나"(EVENT)에도, "전체 이벤트"(ALL)에도 같은 로직(WHERE 조건에
	 * eventId 필터만 있고 없고의 차이)으로 대응 가능하므로 Set.of(EVENT, ALL)을 반환하면 된다.
	 * 반대로 시간 구간 기반 검증(상태 전이, 파이프라인 간 정합성 등)은 Set.of(AS_OF_RANGE)처럼
	 * 하나만 반환해도 된다.
	 */
	Set<Scope.ScopeType> supportedScopeTypes();

	/**
	 * 실제 검증 로직 수행.
	 *
	 * @param scope 검증 대상 범위. supportedScopeType()과 일치하지 않으면 호출 전 Runner가 걸러낸다.
	 * @return 검증 결과 (pass 여부 + 불일치 상세). checkName/triggerType/executedAt 등
	 *         실행 메타데이터는 Runner가 채워서 VerificationResult로 감싼다 — Check는 몰라도 된다.
	 */
	CheckOutcome check(Scope scope);

	/**
	 * check() 메서드가 반환하는 원시 결과.
	 * 순수 데이터 그릇이라 Lombok으로 보일러플레이트를 제거했다.
	 * 생성자는 private으로 감춰서 pass()/fail() 정적 팩토리로만 만들 수 있게 한다.
	 */
	@Getter
	@ToString
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	class CheckOutcome {
		private final boolean pass;
		private final Map<String, Object> diffDetail;

		public static CheckOutcome pass() {
			return new CheckOutcome(true, Collections.emptyMap());
		}

		public static CheckOutcome fail(Map<String, Object> diffDetail) {
			return new CheckOutcome(false, Map.copyOf(diffDetail));
		}
	}

	/**
	 * Check가 지원하지 않는 Scope로 호출되었을 때 던지는 예외.
	 */
	class UnsupportedScopeException extends RuntimeException {
		public UnsupportedScopeException(String checkName, Scope.ScopeType given, Set<Scope.ScopeType> supported) {
			super(String.format("%s does not support scope type %s (expected %s)", checkName, given, supported));
		}
	}
}