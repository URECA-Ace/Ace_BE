package com.ace.coupon.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.ace.common.transaction.AfterCommitExecutor;
import com.ace.coupon.entity.CouponHistory;
import com.ace.coupon.entity.CouponIssue;
import com.ace.coupon.enums.CouponIssueStatus;
import com.ace.coupon.repository.CouponHistoryRepository;
import com.ace.coupon.repository.CouponIssueRepository;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class CouponExpirationProcessor {

	private final CouponHistoryRepository couponHistoryRepository;
	private final CouponIssueRepository couponIssueRepository;
	private final MeterRegistry meterRegistry;

	@Transactional
	public int processChunk(List<CouponIssue> targets, LocalDateTime now) {
		if (targets == null || targets.isEmpty()) {
			return 0;
		}

		List<Long> ids = targets.stream().map(CouponIssue::getId).toList();
		int actualExpired = couponIssueRepository.bulkExpire(ids);

		List<CouponHistory> histories = new ArrayList<>(targets.size());

		for (CouponIssue issue : targets) {
			CouponHistory history = CouponHistory.builder()
					.couponIssue(issue)
					.fromStatus(CouponIssueStatus.ISSUED)
					.toStatus(CouponIssueStatus.EXPIRED)
					.actor("SYSTEM")
					.reason("EXPIRED_BY_SCHEDULE")
					.occurredAt(now)
					.recordedAt(now)
					.eventUid("EXPIRE:" + issue.getId())
					.build();

			histories.add(history);
		}

		couponHistoryRepository.saveAll(histories);
		AfterCommitExecutor.execute(() -> meterRegistry.counter("coupon.state.change",
				"result", "success",
				"result_label", "성공",
				"from", CouponIssueStatus.ISSUED.name(),
				"from_label", CouponStateProcessor.STATE_LABELS.get(CouponIssueStatus.ISSUED),
				"to", CouponIssueStatus.EXPIRED.name(),
				"to_label", CouponStateProcessor.STATE_LABELS.get(CouponIssueStatus.EXPIRED)).increment(actualExpired));
		return actualExpired;
	}
}
