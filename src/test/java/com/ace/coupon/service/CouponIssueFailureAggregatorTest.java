package com.ace.coupon.service;

import com.ace.event.coupon.CouponIssueFailedBatchEvent;
import com.ace.event.coupon.CouponIssueFailedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CouponIssueFailureAggregatorTest {

	private ApplicationEventPublisher eventPublisher;
	private CouponIssueFailureAggregator aggregator;

	@BeforeEach
	void setUp() {
		eventPublisher = Mockito.mock(ApplicationEventPublisher.class);
		aggregator = new CouponIssueFailureAggregator(eventPublisher);
	}

	@Test
	@DisplayName("같은 회차+사유로 여러 번 기록해도 flush 시 건수를 합쳐 한 번만 발행한다")
	void aggregatesSameKeyIntoOneEvent() {
		for (int i = 0; i < 3; i++) {
			aggregator.record(100L, CouponIssueFailedEvent.FailReason.SOLD_OUT);
		}

		aggregator.flush();

		ArgumentCaptor<CouponIssueFailedBatchEvent> captor =
				ArgumentCaptor.forClass(CouponIssueFailedBatchEvent.class);
		verify(eventPublisher).publishEvent(captor.capture());
		CouponIssueFailedBatchEvent event = captor.getValue();
		assertThat(event.getCouponEventId()).isEqualTo(100L);
		assertThat(event.getReason()).isEqualTo(CouponIssueFailedEvent.FailReason.SOLD_OUT);
		assertThat(event.getCount()).isEqualTo(3L);
	}

	@Test
	@DisplayName("회차+사유 조합이 다르면 각각 별도로 발행한다")
	void publishesSeparateEventsPerKey() {
		aggregator.record(100L, CouponIssueFailedEvent.FailReason.SOLD_OUT);
		aggregator.record(100L, CouponIssueFailedEvent.FailReason.ALREADY_ISSUED);
		aggregator.record(200L, CouponIssueFailedEvent.FailReason.SOLD_OUT);

		aggregator.flush();

		ArgumentCaptor<CouponIssueFailedBatchEvent> captor =
				ArgumentCaptor.forClass(CouponIssueFailedBatchEvent.class);
		verify(eventPublisher, Mockito.times(3)).publishEvent(captor.capture());
		List<CouponIssueFailedBatchEvent> events = captor.getAllValues();
		assertThat(events).extracting(CouponIssueFailedBatchEvent::getCount)
				.containsExactly(1L, 1L, 1L);
	}

	@Test
	@DisplayName("기록이 없으면 flush해도 아무것도 발행하지 않는다")
	void doesNotPublishWhenNothingRecorded() {
		aggregator.flush();

		verify(eventPublisher, never()).publishEvent(Mockito.any(CouponIssueFailedBatchEvent.class));
	}

	@Test
	@DisplayName("flush 후 카운터가 리셋되어 다음 flush에서는 재발행하지 않는다")
	void resetsCounterAfterFlush() {
		aggregator.record(100L, CouponIssueFailedEvent.FailReason.SOLD_OUT);

		aggregator.flush();
		aggregator.flush();

		verify(eventPublisher, Mockito.times(1)).publishEvent(Mockito.any(CouponIssueFailedBatchEvent.class));
	}
}
