package com.ace.consistency.common;

import com.ace.consistency.entity.VerificationResultEntity;
import com.ace.consistency.repository.VerificationResultRepository;
import com.ace.coupon.repository.CouponEventRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 정합성 검증의 공통 실행 엔진.
 *
 * 이 클래스는 "무엇을 검증하는지"는 전혀 모른다. 오직 다음 관심사만 책임진다:
 *   1) 넘겨받은 ConsistencyCheck 목록을 순차 실행한다.
 *   2) 개별 Check가 예외를 던지더라도 나머지 Check 실행에 영향을 주지 않는다.
 *   3) 모든 결과를 VerificationResult로 통일해 저장소에 기록한다.
 * 트리거(이벤트 리스너 / 스케줄러 / API 컨트롤러)는 "어떤 Check들을, 어떤 Scope로,
 * 어떤 TriggerType으로 실행할지"만 결정해서 이 Runner에 위임한다.
 * 트리거 종류나 Check 종류가 늘어나도 이 클래스는 수정할 필요가 없다.
 */
@Component
@RequiredArgsConstructor
public class ConsistencyVerificationRunner {

	private static final Logger log = LoggerFactory.getLogger(ConsistencyVerificationRunner.class);

	private final VerificationResultRepository resultRepository;
	private final CouponEventRepository couponEventRepository;

	/**
	 * 여러 Check를 지정된 Scope, TriggerType으로 순차 실행한다.
	 *
	 * 순차 실행을 기본으로 하는 이유: Check들이 대부분 대용량 집계 쿼리라서
	 * 동시에 여러 개를 병렬로 돌리면 DB/Redis/Kafka에 순간 부하가 몰릴 수 있다.
	 * 병렬성이 필요해지면 트리거 쪽에서 이 메서드를 비동기로 감싸서 호출하는 편이
	 * Runner 내부에서 스레드풀을 갖는 것보다 예측 가능하다.
	 *
	 * @param checks       실행할 Check 목록
	 * @param scope        검증 대상 범위
	 * @param triggerType  이 실행을 유발한 트리거 종류 (기록용)
	 * @return 실행된 모든 Check의 결과 목록 (저장 완료된 상태)
	 */
	public List<VerificationResult> run(List<ConsistencyCheck> checks, Scope scope, TriggerType triggerType) {
		Objects.requireNonNull(checks, "checks must not be null");
		Objects.requireNonNull(scope, "scope must not be null");
		Objects.requireNonNull(triggerType, "triggerType must not be null");

		if (checks.isEmpty()) {
			log.warn("run() called with empty check list. scope={}, trigger={}", scope, triggerType);
			return List.of();
		}

		if(scope.getType() == Scope.ScopeType.EVENT){
			Long eventId = scope.getEventId();
			if (!couponEventRepository.existsById(eventId)) {
				throw new EntityNotFoundException("해당 eventId는 존재하지 않습니다. eventId: " + eventId);
			}
		}

		log.info("Starting consistency verification. trigger={}, scope={}, checkCount={}",
				triggerType, scope, checks.size());

		List<VerificationResult> results = checks.stream()
				.map(check -> runSingleCheck(check, scope, triggerType))
				.toList();

		resultRepository.saveAll(results.stream().map(VerificationResultEntity::from).toList());

		long failCount = results.stream().filter(r -> !r.isPass()).count();
		if (failCount > 0) {
			log.warn("Consistency verification finished with {} failing check(s) out of {}. trigger={}, scope={}",
					failCount, results.size(), triggerType, scope);
		} else {
			log.info("Consistency verification finished. All {} check(s) passed. trigger={}, scope={}",
					results.size(), triggerType, scope);
		}

		return results;
	}

	/**
	 * Check 하나를 실행하고, 어떤 예외가 나더라도 이 메서드 밖으로 던지지 않는다.
	 * (한 Check의 실패가 나머지 Check들의 실행을 막지 않도록 격리하는 지점)
	 */
	private VerificationResult runSingleCheck(ConsistencyCheck check, Scope scope, TriggerType triggerType) {
		long start = System.currentTimeMillis();
		LocalDateTime executedAt = LocalDateTime.now();

		// 1) Scope 지원 여부 사전 검증 — Check가 지원 안 하는 스코프로 실행되는 것을 방지
		if (!check.supportedScopeTypes().contains(scope.getType())) {
			Throwable cause = new ConsistencyCheck.UnsupportedScopeException(
					check.getName(), scope.getType(), check.supportedScopeTypes());
			log.error("Check {} does not support scope type {}", check.getName(), scope.getType(), cause);
			return VerificationResult.error(check.getName(), triggerType, scope, cause,
					executedAt, System.currentTimeMillis() - start);
		}

		// 2) 실제 검증 실행 — 예외는 여기서 잡아서 ERROR 상태로 변환 (다른 Check로 전파 방지)
		try {
			ConsistencyCheck.CheckOutcome outcome = check.check(scope);
			long duration = System.currentTimeMillis() - start;

			if (outcome.isPass()) {
				return VerificationResult.pass(check.getName(), triggerType, scope, executedAt, duration);
			} else {
				log.warn("Check {} FAILED. scope={}, diff={}", check.getName(), scope, outcome.getDiffDetail());
				return VerificationResult.fail(check.getName(), triggerType, scope, outcome.getViolationCount(), outcome.getDiffDetail(),
						executedAt, duration);
			}
		} catch (Exception ex) {
			long duration = System.currentTimeMillis() - start;
			log.error("Check {} threw an exception during execution. scope={}", check.getName(), scope, ex);
			return VerificationResult.error(check.getName(), triggerType, scope, ex, executedAt, duration);
		}
	}
}