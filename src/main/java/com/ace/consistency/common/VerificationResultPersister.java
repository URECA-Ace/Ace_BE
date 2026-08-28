package com.ace.consistency.common;

import com.ace.consistency.entity.VerificationResultEntity;
import com.ace.consistency.entity.VerificationViolationEntity;
import com.ace.consistency.repository.VerificationResultRepository;
import com.ace.common.transaction.AfterCommitExecutor;
import com.ace.event.consistency.ConsistencyStepCompletedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import com.ace.consistency.repository.VerificationViolationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

// 정합성 검증 DB 저장 시 정합성 검증이 실패한 경우 실패 알림을 보낼 때, Transaction 처리를 하기 위해 별도로 컴포넌트로 분리
// Runner와 같은 클래스에서 작성하면 AOP가 작동을 안해서 별도로 뺐습니다.
@Component
@RequiredArgsConstructor
public class VerificationResultPersister {

	private final VerificationResultRepository resultRepository;
	private final VerificationViolationRepository violationRepository;
	private final ApplicationEventPublisher eventPublisher;
	private final MeterRegistry meterRegistry;

	// Grafana 범례에 클래스명/enum명 대신 한글로 표시하기 위한 라벨. check/status/scope 태그와 별도로
	// check_label/status_label/scope_label 태그로 함께 심어서, legendFormat이 이 태그를 그대로 참조하게 한다.
	private static final Map<String, String> CHECK_LABELS = Map.ofEntries(
			Map.entry("StockConsistencyCheck", "재고 정합성"),
			Map.entry("RedisMysqlLossConsistencyCheck", "Redis-MySQL 유실"),
			Map.entry("StateMachineConsistencyCheck", "상태 전이"),
			Map.entry("CouponIssueStructuralConsistencyCheck", "발급 구조 정합성"),
			Map.entry("CouponIssueHistoryStateConsistencyCheck", "발급 이력 상태 정합성"),
			Map.entry("CouponHistoryStructuralConsistencyCheck", "쿠폰 이력 구조 정합성"),
			Map.entry("CouponExpirationLagConsistencyCheck", "쿠폰 만료 지연"),
			Map.entry("IssueHistoryTimeSyncConsistencyCheck", "이력 시간 동기화")
	);

	private static final Map<VerificationResult.Status, String> STATUS_LABELS = Map.of(
			VerificationResult.Status.PASS, "정상",
			VerificationResult.Status.FAIL, "실패",
			VerificationResult.Status.ERROR, "에러"
	);

	private static final Map<Scope.ScopeType, String> SCOPE_LABELS = Map.of(
			Scope.ScopeType.EVENT, "이벤트 지정",
			Scope.ScopeType.AS_OF_RANGE, "기간 지정",
			Scope.ScopeType.ALL, "전체"
	);

	/**
	 * @return 저장된 VerificationResultEntity 목록 (results와 같은 순서).
	 */
	@Transactional
	public List<VerificationResultEntity> saveAndNotify(List<VerificationResult> results, Scope scope, TriggerType triggerType) {
		List<VerificationResultEntity> savedResults = saveResultsAndViolations(results);

		AfterCommitExecutor.execute(() -> results.forEach(this::recordMetric));

		//todo: notify 도메인 머지 후 주석해제
		/*
		results.stream()
				.filter(r -> !r.isPass())
				.forEach(r -> eventPublisher.publishEvent(
						ConsistencyCheckFailedEvent.builder()
								.checkName(r.getCheckName())
								.triggerType(triggerType.name())
								.scopeDescription(scope.toString())
								.violationCount(r.getViolationCount())
								.diffDetail(r.getDiffDetail())
								.detectedAt(LocalDateTime.now())
								.build()
				));
		*/

		return savedResults;
	}

	/**
	 * ALL 스코프 배치 전용 — 완료 Step은 결과 저장과 재시작 전후 누적 위반 행의 연결을
	 * 하나의 트랜잭션으로 묶는다. 실패 Step은 ERROR 결과만 저장하고 임시 행을 보존한다.
	 * 결과 저장과 위반 행 연결을 별도 트랜잭션으로 나누면, 결과 저장이 커밋된 직후 연결 전에
	 * 장애가 났을 때 그 위반 행들이 verification_result와 영영 연결되지 못한 채 고아로 남고,
	 * 이후 정리 스케줄러에 지워져 그 결과는 복구가 영구히 불가능해질 수 있다.
	 *
	 * @return 저장된 VerificationResultEntity
	 */
	@Transactional
	public VerificationResultEntity saveStepResult(VerificationResult result, Long jobInstanceId,
												 String stepName, boolean stepIncomplete) {
		VerificationResultEntity saved = saveResultsAndViolations(List.of(result)).getFirst();

		// 실패한 Step의 reader 위치와 violationCount는 재시작 시 복원된다. 같은 이유로
		// 이미 커밋된 위반 행도 유지하고, 최종 완료된 Step에서만 결과에 연결한다.
		if (!stepIncomplete) {
			int linked = violationRepository.linkToResult(jobInstanceId, stepName, saved.getId());
			if (linked != result.getViolationCount()) {
				// 일시적인 DB 오류라면 트랜잭션 롤백 후 재시작으로 복구될 수 있다. 하지만 실제로
				// 임시 위반 행이 유실된 경우 reader는 이미 끝까지 진행된 상태라 같은 JobInstance를
				// 재시작해도 행이 다시 만들어지지 않는다. 이 경우 재시작 기한이 지나면 새로운 ALL
				// 검증을 처음부터 실행해야 한다.
				throw new IllegalStateException("Batch violation count mismatch. expected="
						+ result.getViolationCount() + ", linked=" + linked
						+ ", jobInstanceId=" + jobInstanceId + ", stepName=" + stepName);
			}
		}

		AfterCommitExecutor.execute(() -> recordMetric(result));

		eventPublisher.publishEvent(ConsistencyStepCompletedEvent.builder()
				.checkName(result.getCheckName())
				.triggerType(result.getTriggerType().name())
				.status(result.getStatus().name())
				.violationCount(result.getViolationCount())
				.completedAt(LocalDateTime.now())
				.build());

		return saved;
	}

	private void recordMetric(VerificationResult result) {
		String checkName = result.getCheckName();
		VerificationResult.Status status = result.getStatus();
		Scope.ScopeType scopeType = result.getScope().getType();

		meterRegistry.counter("consistency.verification",
				"check", checkName,
				"check_label", CHECK_LABELS.getOrDefault(checkName, checkName),
				"status", status.name(),
				"status_label", STATUS_LABELS.get(status),
				"scope", scopeType.name(),
				"scope_label", SCOPE_LABELS.get(scopeType)).increment();
	}

	private List<VerificationResultEntity> saveResultsAndViolations(List<VerificationResult> results) {
		List<VerificationResultEntity> savedResults = resultRepository.saveAll(
				results.stream().map(VerificationResultEntity::from).toList()
		);

		for (int i = 0; i < results.size(); i++) {
			VerificationResult result = results.get(i);
			if (result.getViolations().isEmpty()) {
				continue;
			}
			Long verificationResultId = savedResults.get(i).getId();
			violationRepository.saveAll(
					result.getViolations().stream()
							.map(violation -> VerificationViolationEntity.forResult(verificationResultId, violation))
							.toList()
			);
		}

		return savedResults;
	}
}
