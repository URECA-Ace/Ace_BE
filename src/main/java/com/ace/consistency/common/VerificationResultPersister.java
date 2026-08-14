package com.ace.consistency.common;

import com.ace.consistency.entity.VerificationResultEntity;
import com.ace.consistency.repository.VerificationResultRepository;
import com.ace.event.consistency.ConsistencyCheckFailedEvent;
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
	private final ApplicationEventPublisher eventPublisher;

	@Transactional
	public void saveAndNotify(List<VerificationResult> results, Scope scope, TriggerType triggerType) {

		resultRepository.saveAll(
				results.stream().map(VerificationResultEntity::from).toList()
		);

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
	}
}