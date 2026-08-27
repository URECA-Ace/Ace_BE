package com.ace.coupon.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.ace.coupon.entity.CouponHistory;
import com.ace.coupon.entity.CouponIssue;
import com.ace.coupon.enums.CouponIssueStatus;
import com.ace.coupon.repository.CouponHistoryRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class CouponExpirationProcessor {

	private final CouponHistoryRepository couponHistoryRepository;

	@Transactional
	public int processChunk(List<CouponIssue> targets, LocalDateTime now) {
		if (targets == null || targets.isEmpty()) {
			return 0;
		}

		List<CouponHistory> histories = new ArrayList<>(targets.size());

		for (CouponIssue issue : targets) {
			issue.expire();

			CouponHistory history = CouponHistory.builder()
					.couponIssue(issue)
					.fromStatus(CouponIssueStatus.ISSUED)
					.toStatus(CouponIssueStatus.EXPIRED)
					.actor("SYSTEM")
					.reason("EXPIRED_BY_SCHEDULE")
					.occurredAt(now)
					.recordedAt(now)
					.build();

			histories.add(history);
		}

		couponHistoryRepository.saveAll(histories);
		return targets.size();
	}
}
