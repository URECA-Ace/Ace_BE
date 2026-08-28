package com.ace.consistency.common;

import com.ace.consistency.entity.VerificationResultEntity;
import com.ace.consistency.entity.VerificationViolationEntity;
import com.ace.consistency.repository.VerificationResultRepository;
import com.ace.consistency.repository.VerificationViolationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

// 정합성 검증 DB 저장 시 정합성 검증이 실패한 경우 실패 알림을 보낼 때, Transaction 처리를 하기 위해 별도로 컴포넌트로 분리
// Runner와 같은 클래스에서 작성하면 AOP가 작동을 안해서 별도로 뺐습니다.
@Component
@RequiredArgsConstructor
public class VerificationResultPersister {

	private final VerificationResultRepository resultRepository;
	private final VerificationViolationRepository violationRepository;
	//todo: notify 도메인 머지 후 주석해제
	private final ApplicationEventPublisher eventPublisher;

	/**
	 * @return 저장된 VerificationResultEntity 목록 (results와 같은 순서).
	 */
	@Transactional
	public List<VerificationResultEntity> saveAndNotify(List<VerificationResult> results, Scope scope, TriggerType triggerType) {
		List<VerificationResultEntity> savedResults = saveResultsAndViolations(results);

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
	 * ALL 스코프 배치 전용 — Step 종료 시점에 결과 저장과, writer가 청크마다 stepExecutionId로
	 * 임시 태깅해둔 위반 행의 연결(성공)/일괄 삭제(실패)를 하나의 트랜잭션으로 묶는다.
	 * 결과 저장과 위반 행 연결을 별도 트랜잭션으로 나누면, 결과 저장이 커밋된 직후 연결 전에
	 * 장애가 났을 때 그 위반 행들이 verification_result와 영영 연결되지 못한 채 고아로 남고,
	 * 이후 정리 스케줄러에 지워져 그 결과는 복구가 영구히 불가능해질 수 있다.
	 *
	 * @return 저장된 VerificationResultEntity
	 */
	@Transactional
	public VerificationResultEntity saveStepResultAndLinkViolations(VerificationResult result, Long stepExecutionId, boolean stepFailed) {
		VerificationResultEntity saved = saveResultsAndViolations(List.of(result)).getFirst();

		if (stepFailed) {
			violationRepository.deleteByStepExecutionId(stepExecutionId);
		} else {
			violationRepository.linkToResult(stepExecutionId, saved.getId());
		}

		return saved;
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