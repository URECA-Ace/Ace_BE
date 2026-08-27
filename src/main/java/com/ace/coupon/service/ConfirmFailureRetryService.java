package com.ace.coupon.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.ace.coupon.entity.IssueFailureLog;
import com.ace.coupon.persistence.IssuePersistenceCoordinator;
import com.ace.coupon.persistence.failure.IssueFailureStage;
import com.ace.coupon.redis.CouponIssueConfirmResult;
import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.redis.RedisCouponIssueProcessor;
import com.ace.coupon.repository.IssueFailureLogRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 확정만 실패한 건을 다시 확정
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfirmFailureRetryService {

	private static final int RETRY_BATCH_SIZE = 100;
	private static final int MAX_PAGES = 50;
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

	public SweepResult retryFailedConfirmations() {
		int scanned = 0;
		int resolved = 0;
		int alreadyResolved = 0;
		int expired = 0;
		int notRetryable = 0;
		int retryFailed = 0;

		long lastSeenId = 0L;
		for (int page = 0; page < MAX_PAGES; page++) {
			List<IssueFailureLog> targets = failureLogRepository.findRetryTargets(
					IssueFailureStage.CONFIRM,
					RETRYABLE_RESULTS,
					lastSeenId,
					PageRequest.of(0, RETRY_BATCH_SIZE));
			if (targets.isEmpty()) {
				break;
			}

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

			lastSeenId = targets.get(targets.size() - 1).getId();
			if (targets.size() < RETRY_BATCH_SIZE) {
				break;
			}
		}

		if (scanned == 0) {
			// 회수할 대상이 없으면 상태가 달라지지 않았다
			return SweepResult.idle();
		}

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
		CouponIssueConfirmResult confirmResult;
		try {
			confirmResult = issueProcessor.confirm(
					failure.getEventId(),
					failure.getUserId(),
					UUID.fromString(failure.getRequestId()));
		} catch (RuntimeException exception) {
			log.warn("확정 재처리 호출에 실패했습니다. failureId={}, requestId={}",
					failure.getId(), failure.getRequestId(), exception);
			return ConfirmFailureRetryResult.RETRY_FAILED;
		}

		return switch (confirmResult) {
			case CONFIRMED_NOW -> markResolved(failure, ConfirmFailureRetryResult.RESOLVED);
			case ALREADY_CONFIRMED -> markResolved(failure, ConfirmFailureRetryResult.ALREADY_RESOLVED);
			// 요청 레코드가 사라짐
			case REQUEST_NOT_FOUND -> markUnrecoverable(
					failure, confirmResult, ConfirmFailureRetryResult.EXPIRED);
			case NOT_CONFIRMABLE, CORRUPTED_STATE, INVALID_ARGUMENT -> markUnrecoverable(
					failure, confirmResult, ConfirmFailureRetryResult.NOT_RETRYABLE);
			// 다음 주기에 다시 시도
			case INTERNAL_WRITE_ERROR -> ConfirmFailureRetryResult.RETRY_FAILED;
		};
	}

	private ConfirmFailureRetryResult markResolved(
			IssueFailureLog failure, ConfirmFailureRetryResult result) {
		failure.resolve(LocalDateTime.now(properties.zoneId()));
		failureLogRepository.save(failure);
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
		failureLogRepository.save(failure);
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

		// 회수할 대상이 없던 주기
		static SweepResult idle() {
			return new SweepResult(0, 0, 0, 0, 0, 0, 0L, List.of());
		}

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
