package com.ace.consistency.recovery.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ace.consistency.recovery.RecoveryOutcome;
import com.ace.consistency.recovery.RecoveryResult;
import com.ace.consistency.recovery.repository.RecoveryResultRepository;

import lombok.RequiredArgsConstructor;

/** 복구 Outcome 이력을 Dispatcher 트랜잭션과 분리해 저장한다. */
@Component
@RequiredArgsConstructor
public class RecoveryResultRecorder {

	private final RecoveryResultRepository repository;

	/**
	 * Issue/History 복구와 이후 재검증은 별도 단계이므로, 실행 결과 이력은 outcome 단위로 커밋한다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public RecoveryResult record(Long verificationResultId, RecoveryOutcome outcome) {
		return repository.save(RecoveryResult.from(verificationResultId, outcome, LocalDateTime.now()));
	}
}
