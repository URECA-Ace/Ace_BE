package com.ace.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.dto.response.CouponStateChangeResponse;
import com.ace.coupon.entity.CouponStateIdempotency;
import com.ace.coupon.enums.CouponIssueStatus;
import com.ace.coupon.repository.CouponStateIdempotencyRepository;

class CouponStateServiceImplTest {

    private CouponStateProcessor processor;
    private CouponStateIdempotencyRepository idempotencyRepository;
    private CouponStateServiceImpl couponStateService;

    private final Long ISSUE_ID = 1L;
    private final Long USER_ID = 100L;
    private final Long EVENT_ID = 10L;

    @BeforeEach
    void setUp() {
        processor = Mockito.mock(CouponStateProcessor.class);
        idempotencyRepository = Mockito.mock(CouponStateIdempotencyRepository.class);
        couponStateService = new CouponStateServiceImpl(processor, idempotencyRepository);
    }

    @Test
    @DisplayName("[정상] Processor에게 위임하여 성공 응답을 반환한다")
    void useCoupon_success() {
        UUID key = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        CouponStateChangeResponse expected = new CouponStateChangeResponse(
                key, ISSUE_ID, EVENT_ID, USER_ID,
                CouponIssueStatus.ISSUED, CouponIssueStatus.USED, now);

        given(processor.processStateChange(
                eq(ISSUE_ID), eq(USER_ID), eq(key), eq(CouponIssueStatus.USED), eq("결제")))
                .willReturn(expected);

        CouponStateChangeResponse actual = couponStateService.use(ISSUE_ID, USER_ID, key, "결제");

        assertThat(actual).isEqualTo(expected);
        verify(processor).processStateChange(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("[멱등성 복원] UNIQUE 충돌 시 같은 Fingerprint면 최초 응답을 200 OK로 복원한다")
    void duplicateKey_sameFingerprint_restoresResponse() {
        UUID key = UUID.randomUUID();
        LocalDateTime firstOccurredAt = LocalDateTime.now().minusMinutes(5);

        given(processor.processStateChange(any(), any(), any(), any(), any()))
                .willThrow(new DataIntegrityViolationException("Duplicate entry"));

        CouponStateIdempotency existing = CouponStateIdempotency.builder()
                .eventUid(key.toString())
                .issueId(ISSUE_ID).userId(USER_ID)
                .targetStatus(CouponIssueStatus.USED)
                .fromStatus(CouponIssueStatus.ISSUED)
                .eventId(EVENT_ID)
                .occurredAt(firstOccurredAt)
                .createdAt(firstOccurredAt)
                .build();
        given(idempotencyRepository.findByEventUid(key.toString()))
                .willReturn(Optional.of(existing));

        CouponStateChangeResponse response = couponStateService.use(ISSUE_ID, USER_ID, key, "재시도");

        assertThat(response.currentStatus()).isEqualTo(CouponIssueStatus.USED);
        assertThat(response.changedAt()).isEqualTo(firstOccurredAt);
    }

    @Test
    @DisplayName("[멱등성 충돌] 같은 키를 다른 issueId/userId/action에 사용하면 409 에러 발생")
    void duplicateKey_differentFingerprint_throws409() {
        UUID key = UUID.randomUUID();

        given(processor.processStateChange(any(), any(), any(), any(), any()))
                .willThrow(new DataIntegrityViolationException("Duplicate entry"));

        CouponStateIdempotency existing = CouponStateIdempotency.builder()
                .eventUid(key.toString())
                .issueId(ISSUE_ID).userId(999L)
                .targetStatus(CouponIssueStatus.USED)
                .createdAt(LocalDateTime.now())
                .build();
        given(idempotencyRepository.findByEventUid(key.toString()))
                .willReturn(Optional.of(existing));

        assertThatThrownBy(() -> couponStateService.use(ISSUE_ID, USER_ID, key, "재시도"))
                .isInstanceOf(CouponException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IDEMPOTENCY_CONFLICT);
    }

    @Test
    @DisplayName("[취소] cancel 호출 시 Processor에 ISSUED 타겟으로 위임한다")
    void cancelCoupon_delegatesToProcessor() {
        UUID key = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        CouponStateChangeResponse expected = new CouponStateChangeResponse(
                key, ISSUE_ID, EVENT_ID, USER_ID,
                CouponIssueStatus.USED, CouponIssueStatus.ISSUED, now);

        given(processor.processStateChange(
                eq(ISSUE_ID), eq(USER_ID), eq(key), eq(CouponIssueStatus.ISSUED), eq("취소")))
                .willReturn(expected);

        CouponStateChangeResponse actual = couponStateService.cancel(ISSUE_ID, USER_ID, key, "취소");

        assertThat(actual.currentStatus()).isEqualTo(CouponIssueStatus.ISSUED);
    }
}
