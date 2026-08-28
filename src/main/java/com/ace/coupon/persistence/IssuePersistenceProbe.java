package com.ace.coupon.persistence;

import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ace.coupon.entity.CouponIssue;
import com.ace.coupon.repository.CouponIssueRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 저장 실패로 보이는 요청이 실제로 저장됐는지 확인
// persist() 예외가 항상 롤백을 뜻하지는 않는다
// 커밋이 반영된 뒤 커넥션이 끊기면 DB 에는 행이 있는데 호출자는 예외를 받는다
@Slf4j
@Component
@RequiredArgsConstructor
public class IssuePersistenceProbe {

	public enum Result {
		// 저장O = 원복하면 안 된다
		PERSISTED,
		// 저장X = 원복 대상
		ABSENT,
		// 판별 실패 = 원복하지 않고 복구 대상으로 남긴다
		UNVERIFIED
	}

	private final CouponIssueRepository couponIssueRepository;

	// 실패한 트랜잭션 위에서 읽으면 같은 커넥션 상태에 묶이므로 새 트랜잭션으로 분리한다
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public Result probe(IssueRecord record) {
		try {
			Optional<CouponIssue> stored =
					couponIssueRepository.findByRequestId(record.requestId().toString());
			if (stored.isEmpty()) {
				return Result.ABSENT;
			}
			if (matches(stored.get(), record)) {
				return Result.PERSISTED;
			}

			CouponIssue other = stored.get();
			log.warn(
					"같은 requestId의 다른 발급이 저장되어 있어 현재 요청은 미저장으로 판단합니다: "
							+ "requestId={}, requestedEventId={}, requestedUserId={}, requestedSequence={}, "
							+ "storedIssueId={}, storedEventId={}, storedUserId={}, storedSequence={}",
					record.requestId(),
					record.campaignId(),
					record.userId(),
					record.issueSequence(),
					other.getId(),
					other.getCouponEvent().getId(),
					other.getUser().getId(),
					other.getIssueSequence());
			return Result.ABSENT;
		} catch (RuntimeException exception) {
			log.error("저장 여부 판별 실패, 원복하지 않습니다: requestId={}", record.requestId(), exception);
			return Result.UNVERIFIED;
		}
	}

	// 실패 기록만 들고 저장 여부를 판별
	// issue_failure_log 재처리용
	// 그때는 원본 IssueRecord 도 Stream 엔트리도 없다
	// issueSequence 는 비어 있을 수 있고, 그러면 request_id UNIQUE 에 기대어 순번 비교를 건너뛴다
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public Result probe(long campaignId, long userId, String requestId, Long issueSequence) {
		try {
			Optional<CouponIssue> stored = couponIssueRepository.findByRequestId(requestId);
			if (stored.isEmpty()) {
				return Result.ABSENT;
			}
			if (matches(stored.get(), campaignId, userId, issueSequence)) {
				return Result.PERSISTED;
			}

			CouponIssue other = stored.get();
			log.warn("같은 requestId의 다른 발급이 저장되어 있어 현재 요청은 미저장으로 판단합니다: "
							+ "requestId={}, requestedEventId={}, requestedUserId={}, requestedSequence={}, "
							+ "storedIssueId={}, storedEventId={}, storedUserId={}, storedSequence={}",
					requestId, campaignId, userId, issueSequence,
					other.getId(), other.getCouponEvent().getId(),
					other.getUser().getId(), other.getIssueSequence());
			return Result.ABSENT;
		} catch (RuntimeException exception) {
			log.error("저장 여부 판별 실패, 원복하지 않습니다: requestId={}", requestId, exception);
			return Result.UNVERIFIED;
		}
	}

	private boolean matches(CouponIssue stored, IssueRecord record) {
		return matches(stored, record.campaignId(), record.userId(), record.issueSequence());
	}

	private boolean matches(CouponIssue stored, long campaignId, long userId, Long issueSequence) {
		if (stored.getCouponEvent().getId().longValue() != campaignId
				|| stored.getUser().getId().longValue() != userId) {
			return false;
		}
		// 순번이 없으면 request_id UNIQUE 에 기대어 회차, 사용자 일치만으로 판단
		return issueSequence == null
				|| stored.getIssueSequence().longValue() == issueSequence;
	}
}
