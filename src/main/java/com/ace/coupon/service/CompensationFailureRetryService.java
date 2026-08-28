package com.ace.coupon.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.ace.coupon.entity.IssueFailureLog;
import com.ace.coupon.persistence.IssuePersistenceCoordinator;
import com.ace.coupon.persistence.IssuePersistenceProbe;
import com.ace.coupon.persistence.failure.IssueFailureStage;
import com.ace.coupon.redis.CouponIssueCompensationResult;
import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.redis.RedisCouponIssueProcessor;
import com.ace.coupon.repository.IssueFailureLogRepository;

import lombok.extern.slf4j.Slf4j;

// 보상(재고 원복)에 실패한 건을 다시 원복
// 되돌리지 못한 재고는 Redis 에 묶여 pending 이 0 으로 안 내려가고 회차 마감을 막는다
// CONFIRM 만 재처리되고 COMPENSATE / DB_INSERT / RELAY 는 아무도 안 보던 구멍을 수정

// 안전 규칙: 되돌리기 전에 저장 여부를 다시 확인
// 이미 저장된 건을 되돌리면 MySQL 에 행이 있는 채로 재고가 복구돼 초과 발급이 된다

// 전제: 인스턴스마다 독립적으로 돈다
// 보상 Lua 는 요청 상태 CAS 안에서 재고를 올려 멱등이라 재고가 두 번 늘지는 않는다
// 다만 backlog 가 크면 Redis / DB 부하가 인스턴스 수만큼 늘어난다
@Slf4j
@Service
public class CompensationFailureRetryService {

	// 경보에 실을 회차 수
	private static final int BLOCKED_EVENT_SAMPLE_SIZE = 20;

	// 이 재처리기가 맡는 단계
	// COMPENSATE 는 보상 호출 자체가 실패한 기록, DB_INSERT / RELAY 는 그 짝으로 남는 저장 실패 기록이다
	private static final Set<IssueFailureStage> STAGES = Set.of(
			IssueFailureStage.COMPENSATE,
			IssueFailureStage.DB_INSERT,
			IssueFailureStage.RELAY);

	// 재고가 아직 묶여 있을 수 있어 다시 시도해 볼 값
	private static final Set<String> RETRYABLE_RESULTS = Set.of(
			IssuePersistenceCoordinator.CALL_FAILED,
			IssuePersistenceCoordinator.COMPENSATION_SKIPPED_UNVERIFIED,
			CouponIssueCompensationResult.INTERNAL_WRITE_ERROR.name());

	// 같은 경보를 주기마다 반복하지 않기 위한 직전 보고 내용
	private final AtomicReference<String> lastReport = new AtomicReference<>();

	private final IssueFailureLogRepository failureLogRepository;
	private final IssuePersistenceProbe persistenceProbe;
	private final RedisCouponIssueProcessor issueProcessor;
	private final CouponIssueRedisProperties properties;

	// 한 주기가 훑는 양의 상한
	// 남은 건은 다음 주기가 이어받는다
	private final int batchSize;

	public CompensationFailureRetryService(
			IssueFailureLogRepository failureLogRepository,
			IssuePersistenceProbe persistenceProbe,
			RedisCouponIssueProcessor issueProcessor,
			CouponIssueRedisProperties properties,
			@Value("${coupon.issue.compensation-retry.batch-size:1000}") int batchSize) {
		this.failureLogRepository = failureLogRepository;
		this.persistenceProbe = persistenceProbe;
		this.issueProcessor = issueProcessor;
		this.properties = properties;
		this.batchSize = batchSize;
	}

	public SweepResult retryFailedCompensations() {
		int scanned = 0;
		int resolved = 0;
		int alreadyResolved = 0;
		int skippedPersisted = 0;
		int expired = 0;
		int notRetryable = 0;
		int retryFailed = 0;

		// 마지막 시도 시각 오름차순으로 한 페이지만 가져온다
		// 커서를 들고 다니지 않아도 시도한 건은 자연히 뒤로 밀린다
		List<IssueFailureLog> targets = failureLogRepository.findRetryTargets(
				STAGES,
				RETRYABLE_RESULTS,
				PageRequest.of(0, batchSize));

		for (IssueFailureLog failure : targets) {
			scanned++;
			switch (retry(failure)) {
				case RESOLVED -> resolved++;
				case ALREADY_RESOLVED -> alreadyResolved++;
				case SKIPPED_PERSISTED -> skippedPersisted++;
				case EXPIRED -> expired++;
				case NOT_RETRYABLE -> notRetryable++;
				case RETRY_FAILED -> retryFailed++;
			}
		}

		// 재시도 대상이 없다는 것과 막힌 회차가 없다는 것은 다르다
		// 되살릴 수 없는 건만 남아 있으면 findRetryTargets 는 비지만 회차는 계속 막혀 있다
		SweepResult result = new SweepResult(
				scanned, resolved, alreadyResolved, skippedPersisted, expired, notRetryable, retryFailed,
				failureLogRepository.countUnrecoverable(STAGES, RETRYABLE_RESULTS),
				failureLogRepository.findBlockedEventIds(
						STAGES, PageRequest.of(0, BLOCKED_EVENT_SAMPLE_SIZE)));
		warnWhenUnrecovered(result);
		return result;
	}

	// 한 건의 실패가 다음 건을 막지 않도록
	public CompensationFailureRetryResult retry(IssueFailureLog failure) {
		LocalDateTime now = LocalDateTime.now(properties.zoneId());
		// 성공/실패와 무관하게 시도를 남겨야 다음 회전에서 뒤로 밀린다
		failure.recordAttempt(now);

		CompensationFailureRetryResult result;
		try {
			result = compensateIfAbsent(failure, now);
		} catch (RuntimeException exception) {
			log.warn("보상 재처리 호출에 실패했습니다. failureId={}, requestId={}",
					failure.getId(), failure.getRequestId(), exception);
			result = CompensationFailureRetryResult.RETRY_FAILED;
		}

		// 시도 기록이 남았으므로 결과와 무관하게 한 번 저장한다
		failureLogRepository.save(failure);
		return result;
	}

	// 저장 여부를 다시 확인한 뒤에만 되돌린다
	// 이미 저장된 건을 되돌리면 MySQL 에 행이 있는 채로 재고가 복구돼 초과 발급이 된다
	private CompensationFailureRetryResult compensateIfAbsent(
			IssueFailureLog failure, LocalDateTime now) {

		IssuePersistenceProbe.Result probed = persistenceProbe.probe(
				failure.getEventId(),
				failure.getUserId(),
				failure.getRequestId(),
				failure.getIssueSequence());

		switch (probed) {
			case PERSISTED -> {
				// 저장이 확인
				// 되돌리면 초과 발급이므로 재처리 대상에서 뺀다
				return markUnrecoverable(
						failure,
						IssuePersistenceCoordinator.COMPENSATION_SKIPPED_PERSISTED,
						CompensationFailureRetryResult.SKIPPED_PERSISTED);
			}
			case UNVERIFIED -> {
				// 판정 값을 바꾸지 않아 다음 주기에 다시 잡힌다
				log.warn("저장 여부를 판별하지 못해 보상을 미룹니다. failureId={}, eventId={}",
						failure.getId(), failure.getEventId());
				return CompensationFailureRetryResult.RETRY_FAILED;
			}
			case ABSENT -> {
				return classify(failure, issueProcessor.compensate(
						failure.getEventId(),
						failure.getUserId(),
						UUID.fromString(failure.getRequestId())), now);
			}
		}
		throw new IllegalStateException("처리되지 않은 저장 판별 결과입니다: " + probed);
	}

	private CompensationFailureRetryResult classify(
			IssueFailureLog failure, CouponIssueCompensationResult compensationResult, LocalDateTime now) {
		return switch (compensationResult) {
			case COMPENSATED -> markResolved(
					failure, compensationResult, now, CompensationFailureRetryResult.RESOLVED);
			case ALREADY_COMPENSATED -> markResolved(
					failure, compensationResult, now, CompensationFailureRetryResult.ALREADY_RESOLVED);
			// 요청 레코드가 사라져 재고를 되돌릴 수단이 없다
			case REQUEST_NOT_FOUND -> markUnrecoverable(
					failure, compensationResult.name(), CompensationFailureRetryResult.EXPIRED);
			case NOT_COMPENSABLE, CORRUPTED_STATE, INVALID_ARGUMENT -> markUnrecoverable(
					failure, compensationResult.name(), CompensationFailureRetryResult.NOT_RETRYABLE);
			// 다음 주기에 다시 시도
			// 판정 값을 바꾸지 않아 대상에 그대로 남는다
			case INTERNAL_WRITE_ERROR -> CompensationFailureRetryResult.RETRY_FAILED;
		};
	}

	private CompensationFailureRetryResult markResolved(
			IssueFailureLog failure,
			CouponIssueCompensationResult compensationResult,
			LocalDateTime now,
			CompensationFailureRetryResult result) {
		failure.updateConfirmResult(compensationResult.name());
		failure.resolve(now);
		log.info("묶여 있던 재고를 되돌렸습니다. failureId={}, eventId={}, result={}",
				failure.getId(), failure.getEventId(), result);
		return result;
	}

	// 판정 값을 갱신해 재처리 대상에서 빼되 해소로 표시하지 않는다
	private CompensationFailureRetryResult markUnrecoverable(
			IssueFailureLog failure, String compensationResult, CompensationFailureRetryResult result) {
		failure.updateConfirmResult(compensationResult);
		log.warn("보상 실패를 회수할 수 없습니다. failureId={}, eventId={}, compensationResult={}",
				failure.getId(), failure.getEventId(), compensationResult);
		return result;
	}

	// 회수하지 못한 건이 어느 회차를 막고 있는지 표시
	private void warnWhenUnrecovered(SweepResult result) {
		if (!result.hasUnrecovered()) {
			lastReport.set(null);
			return;
		}
		// 확인 필요 건은 사람이 손대기 전까지 사라지지 않는다
		// 매번 남기면 같은 경보가 주기마다 반복되므로 달라졌을 때만 남긴다
		String report = result.signature();
		if (report.equals(lastReport.getAndSet(report))) {
			return;
		}
		log.warn("보상 실패를 회수하지 못했습니다. 해당 회차는 pendingQuantity 가 0 으로 내려가지 않아 마감되지 않습니다. "
						+ "scanned={}, recovered={}, skippedPersisted={}, expired={}, notRetryable={}, "
						+ "retryFailed={}, unrecoverable={}, blockedEventIds={}",
				result.scanned(), result.recovered(), result.skippedPersisted(), result.expired(),
				result.notRetryable(), result.retryFailed(), result.unrecoverable(),
				result.blockedEventIds());
	}

	public record SweepResult(
			int scanned,
			int resolved,
			int alreadyResolved,
			int skippedPersisted,
			int expired,
			int notRetryable,
			int retryFailed,
			long unrecoverable,
			List<Long> blockedEventIds) {

		// 경보 중복 판단용
		String signature() {
			return expired + "/" + notRetryable + "/" + retryFailed
					+ "/" + unrecoverable + "/" + blockedEventIds;
		}

		// 재고가 실제로 돌아온 건수
		public int recovered() {
			return resolved + alreadyResolved;
		}

		public boolean hasUnrecovered() {
			return unrecoverable > 0 || retryFailed > 0 || !blockedEventIds.isEmpty();
		}
	}
}
