package com.ace.coupon.service;

import com.ace.event.coupon.CouponIssueFailedBatchEvent;
import com.ace.event.coupon.CouponIssueFailedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

// 발급 실패마다 알림을 하나씩 쏘면, 순간적으로 실패가 몰릴 때(예: 2만 요청 중 1만 실패)
// SSE 메시지도 1만 건이 나가서 프론트가 렉걸린다. 그래서 실패는 요청 경로에서 카운터에만
// lock-free로 쌓아두고, 주기적으로 회차+사유별 건수를 한 번에 요약해서 알린다.
@Component
@RequiredArgsConstructor
public class CouponIssueFailureAggregator {

	private final ApplicationEventPublisher eventPublisher;

	private final Map<FailureKey, LongAdder> counters = new ConcurrentHashMap<>();

	public void record(Long couponEventId, CouponIssueFailedEvent.FailReason reason) {
		counters.computeIfAbsent(new FailureKey(couponEventId, reason), key -> new LongAdder())
				.increment();
	}

	@Scheduled(fixedDelay = 5000)
	public void flush() {
		for (Map.Entry<FailureKey, LongAdder> entry : counters.entrySet()) {
			long count = entry.getValue().sumThenReset();
			if (count == 0) {
				continue;
			}
			FailureKey key = entry.getKey();
			eventPublisher.publishEvent(CouponIssueFailedBatchEvent.builder()
					.couponEventId(key.couponEventId())
					.reason(key.reason())
					.count(count)
					.windowEndedAt(LocalDateTime.now())
					.build());
		}
	}

	// 회차+사유 조합별로 카운터를 나눈다. 회차가 계속 새로 생겨도 조합 가짓수는
	// 실무적으로 매우 작아서(회차 수 × FailReason 4종) 굳이 정리하지 않는다.
	private record FailureKey(Long couponEventId, CouponIssueFailedEvent.FailReason reason) {
	}
}
