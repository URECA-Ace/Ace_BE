package com.ace.consistency.common;

import com.ace.common.ErrorCode;
import com.ace.common.exception.ConsistencyCheckException;
import com.ace.consistency.batch.ConsistencyBatchJobFactory;
import com.ace.consistency.batch.ConsistencyJobExecutionListener;
import com.ace.coupon.repository.CouponEventRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.DuplicateJobException;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.*;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
@Slf4j
public class ConsistencyVerificationRunner {

	private final VerificationResultPersister resultPersister;
	private final CouponEventRepository couponEventRepository;
	private final ConsistencyBatchJobFactory jobFactory;
	private final JobOperator asyncJobOperator;
	private final JobRepository jobRepository;
	private final JobRegistry jobRegistry;
	private final List<ConsistencyCheck> allChecks;

	/**
	 * 여러 Check를 지정된 Scope, TriggerType으로 순차 실행한다.
	 * Scope가 EVENT, AS_OF_RANGE인 경우 사용한다.(ALL은 비동기이므로 반환값이 다르다)
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

		if(scope.getType() == Scope.ScopeType.ALL){
			throw new IllegalArgumentException("ALL Scope는 runAysnc()를 사용해주세요.");
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

		resultPersister.saveAndNotify(results, scope, triggerType);

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
	 * ALL 스코프 전용 — 정합성 검증 배치(Spring Batch Job)를 비동기로 실행한다.
	 * Job 완료를 기다리지 않고 JobExecution을 즉시 반환한다.
	 * 개별 Check의 결과(VerificationResult)는 각 Step 완료 시점에 저장되므로,
	 * 이 메서드는 결과 목록을 반환하지 않는다.
	 * 완료 여부는 반환된 JobExecution의 id로 조회하거나(pull), Job 완료 알림(push)으로 확인한다.
	 *
	 * @param checks       실행할 Check 목록
	 * @param scope        검증 대상 범위 (ALL 스코프만 허용)
	 * @param triggerType  이 실행을 유발한 트리거 종류 (기록용)
	 * @return 실행 시작된 배치 Job의 JobExecution
	 */
	public JobExecution runAsync(List<ConsistencyCheck> checks, Scope scope, TriggerType triggerType) {
		Objects.requireNonNull(checks, "checks must not be null");
		Objects.requireNonNull(scope, "scope must not be null");
		Objects.requireNonNull(triggerType, "triggerType must not be null");

		if (scope.getType() != Scope.ScopeType.ALL) {
			throw new IllegalArgumentException(
					"runAsync()는 ALL 스코프 전용입니다. scope=" + scope.getType() + "인 경우 run()을 사용해주세요.");
		}

		if (checks.isEmpty()) {
			throw new IllegalArgumentException("runAsync() called with empty check list.");
		}

		log.info("Starting async consistency verification batch. trigger={}, scope={}, checkCount={}",
				triggerType, scope, checks.size());

		Job job = jobFactory.buildJob(checks, scope, triggerType);

		JobParameters params = new JobParametersBuilder()
				.addLong("runId", System.currentTimeMillis()) // 매 실행마다 새로운 JobInstance로 인식되도록 유일성만 부여
				.toJobParameters();

		try {
			JobExecution execution = asyncJobOperator.start(job, params);
			log.info("Consistency verification batch launched. jobExecutionId={}, trigger={}, scope={}",
					execution.getId(), triggerType, scope);
			return execution;
		} catch (JobExecutionAlreadyRunningException | JobRestartException
				 | JobInstanceAlreadyCompleteException | InvalidJobParametersException ex) {
			throw new IllegalStateException("정합성 검증 배치 실행에 실패했습니다.", ex);
		}
	}

	/**
	 * 실패했거나 중단된 ALL 스코프 배치 Job을, 원래 실행에 쓰였던 것과 동일한
	 * checks/scope/triggerType으로 재시작한다.
	 * {@code consistencyVerificationJob}은 고정 Job 빈이 아니라 매 실행마다 동적으로
	 * 조립되므로, {@link JobOperator#restart}가 이름으로 Job을 다시 찾아낼 수 있도록
	 * 재시작 직전에 동일한 Step 구성으로 Job을 재조립해 {@link JobRegistry}에 등록해둔다.
	 * Step 안의 {@code ItemStream}(EventIdPageReader/CheckResultAccumulatorWriter)이
	 * 마지막 위치를 복원하므로, 이미 COMPLETED된 Step은 다시 실행되지 않는다.
	 *
	 * @param jobExecutionId 재시작할 원래 실행의 JobExecution id
	 * @return 재시작으로 새로 시작된 JobExecution
	 */
	public JobExecution restartRunAsync(long jobExecutionId) {
		JobExecution failedExecution = jobRepository.getJobExecution(jobExecutionId);
		if (failedExecution == null) {
			throw new IllegalArgumentException("해당 jobExecutionId의 실행 기록이 없습니다. jobExecutionId=" + jobExecutionId);
		}

		ExecutionContext context = failedExecution.getExecutionContext();
		List<ConsistencyCheck> checks = resolveChecks(context.getString(ConsistencyJobExecutionListener.RESTART_CHECK_NAMES_KEY));
		LocalDateTime scopeTo = LocalDateTime.parse(context.getString(ConsistencyJobExecutionListener.RESTART_SCOPE_TO_KEY));
		TriggerType triggerType = TriggerType.valueOf(context.getString(ConsistencyJobExecutionListener.RESTART_TRIGGER_TYPE_KEY));
		Scope scope = Scope.all(scopeTo);

		Job job = jobFactory.buildJob(checks, scope, triggerType);
		jobRegistry.unregister(job.getName());
		try {
			jobRegistry.register(job);
			JobExecution restarted = asyncJobOperator.restart(failedExecution);
			log.info("Consistency verification batch restarted. previousExecutionId={}, newExecutionId={}",
					jobExecutionId, restarted.getId());
			return restarted;
		} catch (DuplicateJobException | JobRestartException ex) {
			throw new IllegalStateException("정합성 검증 배치 재시작에 실패했습니다. jobExecutionId=" + jobExecutionId, ex);
		}
	}

	private List<ConsistencyCheck> resolveChecks(String checkNamesCsv) {
		if (checkNamesCsv == null || checkNamesCsv.isBlank()) {
			throw new IllegalStateException("재시작에 필요한 check 정보가 저장되어 있지 않습니다.");
		}
		Set<String> names = Set.of(checkNamesCsv.split(ConsistencyJobExecutionListener.CHECK_NAME_DELIMITER));
		List<ConsistencyCheck> resolved = allChecks.stream()
				.filter(check -> names.contains(check.getName()))
				.toList();
		if (resolved.size() != names.size()) {
			throw new IllegalStateException("재시작 대상 Check 중 일부를 찾을 수 없습니다. expected=" + names
					+ ", resolved=" + resolved.stream().map(ConsistencyCheck::getName).toList());
		}
		return resolved;
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
			Throwable cause = new ConsistencyCheckException(ErrorCode.UNSUPPORTED_SCOPE,
					String.format("%s does not support scope type %s (expected %s)",
							check.getName(), scope.getType(), check.supportedScopeTypes()));
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