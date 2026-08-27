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
import com.ace.coupon.persistence.failure.IssueFailureStage;
import com.ace.coupon.redis.CouponIssueConfirmResult;
import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.redis.RedisCouponIssueProcessor;
import com.ace.coupon.repository.IssueFailureLogRepository;

import lombok.extern.slf4j.Slf4j;

// 확정만 실패한 건을 다시 확정

// 전제: 이 재처리는 인스턴스마다 독립적으로 돈다
// 확정은 CAS 안에서 카운터를 올려 멱등이라 여러 인스턴스가 같은 건을 처리해도 값은 어긋나지 않는다
// 다만 backlog 가 큰 시점에는 Redis / DB 부하가 인스턴스 수만큼 늘어난다
// 다중 인스턴스로 가려면 단일 실행 보장(분산 락 또는 lease)이 먼저 필요하다
@Slf4j
@Service
public class ConfirmFailureRetryService {


	// 경보에 실을 회차 수
	private static final int BLOCKED_EVENT_SAMPLE_SIZE = 20;

	// 재확인해 볼 값
	private static final Set<String> RETRYABLE_RESULTS = Set.of(
			IssuePersistenceCoordinator.CALL_FAILED,
			CouponIssueConfirmResult.INTERNAL_WRITE_ERROR.name());

	// 같은 경보를 주기마다 반복하지 않기 위한 직전 보고 내용
	private final AtomicReference<String> lastReport = new AtomicReference<>();


	private final IssueFailureLogRepository failureLogRepository;
	private final RedisCouponIssueProcessor issueProcessor;
	private final CouponIssueRedisProperties properties;

	// 한 주기가 훑는 양의 상한
	// 남은 건은 다음 주기가 이어받는다
	private final int batchSize;

	public ConfirmFailureRetryService(
			IssueFailureLogRepository failureLogRepository,
			RedisCouponIssueProcessor issueProcessor,
			CouponIssueRedisProperties properties,
			@Value("${coupon.issue.confirm-retry.batch-size:1000}") int batchSize) {
		this.failureLogRepository = failureLogRepository;
		this.issueProcessor = issueProcessor;
		this.properties = properties;
		this.batchSize = batchSize;
	}

	public SweepResult retryFailedConfirmations() {
		int scanned = 0;
		int resolved = 0;
		int alreadyResolved = 0;
		int expired = 0;
		int notRetryable = 0;
		int retryFailed = 0;

		// 마지막 시도 시각 오름차순으로 한 페이지만 가져온다
		// 커서를 들고 다니지 않아도 시도한 건은 자연히 뒤로 밀린다
		List<IssueFailureLog> targets = failureLogRepository.findRetryTargets(
				IssueFailureStage.CONFIRM,
				RETRYABLE_RESULTS,
				PageRequest.of(0, batchSize));

		for (IssueFailureLog failure : targets) {
			scanned++;
			switch (retry(failure)) {
				case RESOLVED -> resolved++;
				case ALREADY_RESOLVED -> alreadyResolved++;
				case EXPIRED -> expired++;
				case NOT_RETRYABLE -> notRetryable++;
				case RETRY_FAILED -> retryFailed++;
			}
		}


		// 재시도 대상이 없다는 것과 막힌 회차가 없다는 것은 다르다
		// 되살릴 수 없는 건만 남아 있으면 findRetryTargets 는 비지만 회차는 계속 막혀 있다
		// 그 상태를 비어 있다고 보고하면 막힌 회차가 은폐된다
		SweepResult result = new SweepResult(
				scanned, resolved, alreadyResolved, expired, notRetryable, retryFailed,
				failureLogRepository.countUnrecoverable(
						IssueFailureStage.CONFIRM, RETRYABLE_RESULTS),
				failureLogRepository.findBlockedEventIds(
						IssueFailureStage.CONFIRM, PageRequest.of(0, BLOCKED_EVENT_SAMPLE_SIZE)));
		warnWhenUnrecovered(result);
		return result;
	}

	// 한 건의 실패가 다음 건을 막지 않도록
	public ConfirmFailureRetryResult retry(IssueFailureLog failure) {
		LocalDateTime now = LocalDateTime.now(properties.zoneId());
		// 성공/실패와 무관하게 시도를 남겨야 다음 회전에서 뒤로 밀린다
		failure.recordAttempt(now);

		ConfirmFailureRetryResult result;
		try {
			result = classify(failure, issueProcessor.confirm(
					failure.getEventId(),
					failure.getUserId(),
					UUID.fromString(failure.getRequestId())), now);
		} catch (RuntimeException exception) {
			log.warn("확정 재처리 호출에 실패했습니다. failureId={}, requestId={}",
					failure.getId(), failure.getRequestId(), exception);
			result = ConfirmFailureRetryResult.RETRY_FAILED;
		}

		// 시도 기록이 남았으므로 결과와 무관하게 한 번 저장한다
		failureLogRepository.save(failure);
		return result;
	}

	private ConfirmFailureRetryResult classify(
			IssueFailureLog failure, CouponIssueConfirmResult confirmResult, LocalDateTime now) {
		return switch (confirmResult) {
			case CONFIRMED_NOW -> markResolved(failure, now, ConfirmFailureRetryResult.RESOLVED);
			case ALREADY_CONFIRMED -> markResolved(
					failure, now, ConfirmFailureRetryResult.ALREADY_RESOLVED);
			// 요청 레코드가 사라졌다. 재확인 결과가 곧 만료 증거다
			case REQUEST_NOT_FOUND -> markUnrecoverable(
					failure, confirmResult, ConfirmFailureRetryResult.EXPIRED);
			case NOT_CONFIRMABLE, CORRUPTED_STATE, INVALID_ARGUMENT -> markUnrecoverable(
					failure, confirmResult, ConfirmFailureRetryResult.NOT_RETRYABLE);
			// 다음 주기에 다시 시도한다. 판정 값을 바꾸지 않아 대상에 그대로 남는다
			case INTERNAL_WRITE_ERROR -> ConfirmFailureRetryResult.RETRY_FAILED;
		};
	}

	private ConfirmFailureRetryResult markResolved(
			IssueFailureLog failure, LocalDateTime now, ConfirmFailureRetryResult result) {
		failure.resolve(now);
		log.info("확정 실패를 회수했습니다. failureId={}, eventId={}, result={}",
				failure.getId(), failure.getEventId(), result);
		return result;
	}

	// 판정 값을 갱신해 재처리 대상에서 빼되 해소로 표시X
	private ConfirmFailureRetryResult markUnrecoverable(
			IssueFailureLog failure,
			CouponIssueConfirmResult confirmResult,
			ConfirmFailureRetryResult result) {
		failure.updateConfirmResult(confirmResult.name());
		log.warn("확정 실패를 회수할 수 없습니다. failureId={}, eventId={}, confirmResult={}",
				failure.getId(), failure.getEventId(), confirmResult);
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
		log.warn("확정 실패를 회수하지 못했습니다. "
						+ "scanned={}, resolved={}, expired={}, notRetryable={}, retryFailed={}, "
						+ "unrecoverable={}, blockedEventIds={}",
				result.scanned(), result.recovered(), result.expired(), result.notRetryable(),
				result.retryFailed(), result.unrecoverable(), result.blockedEventIds());
	}

	public record SweepResult(
			int scanned,
			int resolved,
			int alreadyResolved,
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

		// 확정 카운터가 올라간 건수
		public int recovered() {
			return resolved + alreadyResolved;
		}

		public boolean hasUnrecovered() {
			return unrecoverable > 0 || retryFailed > 0 || !blockedEventIds.isEmpty();
		}
	}
}
