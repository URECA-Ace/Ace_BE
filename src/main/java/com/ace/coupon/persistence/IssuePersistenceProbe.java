package com.ace.coupon.persistence;

import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
			Optional<com.ace.coupon.entity.CouponIssue> stored =
					couponIssueRepository.findByRequestId(record.requestId().toString());
			return stored.isPresent() ? Result.PERSISTED : Result.ABSENT;
		} catch (RuntimeException exception) {
			log.error("저장 여부 판별 실패, 원복하지 않습니다: requestId={}", record.requestId(), exception);
			return Result.UNVERIFIED;
		}
	}
}
