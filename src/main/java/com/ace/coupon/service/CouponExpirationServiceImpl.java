package com.ace.coupon.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.ace.coupon.entity.CouponIssue;
import com.ace.coupon.repository.CouponIssueRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponExpirationServiceImpl implements CouponExpirationService {

	private final CouponIssueRepository couponIssueRepository;
	private final CouponExpirationProcessor processor;

	@Override
	public int expireDueCoupons(int chunkSize) {
		LocalDateTime now = LocalDateTime.now();
		Long lastId = 0L;
		int totalExpiredCount = 0;
		Pageable pageable = PageRequest.of(0, chunkSize);

		while (true) {
			List<CouponIssue> chunk = couponIssueRepository
					.findExpiredIssuesChunk(now, lastId, pageable);

			if (chunk.isEmpty()) {
				break;
			}

			int processed = processor.processChunk(chunk, now);
			totalExpiredCount += processed;
			lastId = chunk.get(chunk.size() - 1).getId();

			if (chunk.size() < chunkSize) {
				break;
			}
		}

		return totalExpiredCount;
	}
}
