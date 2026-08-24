package com.ace.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.dto.response.CouponStateChangeResponse;
import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.entity.CouponHistory;
import com.ace.coupon.entity.CouponIssue;
import com.ace.coupon.enums.CouponIssueStatus;
import com.ace.coupon.redis.CouponIssueRedisProperties;
import com.ace.coupon.repository.CouponHistoryRepository;
import com.ace.coupon.repository.CouponIssueRepository;
import com.ace.user.entity.User;

class CouponStateServiceImplTest {

    private CouponIssueRepository couponIssueRepository;
    private CouponHistoryRepository couponHistoryRepository;
    private CouponStateService couponStateService;

    private final Long ISSUE_ID = 1L;
    private final Long USER_ID = 100L;
    private final Long EVENT_ID = 10L;

    @BeforeEach
    void setUp() {
        couponIssueRepository = Mockito.mock(CouponIssueRepository.class);
        couponHistoryRepository = Mockito.mock(CouponHistoryRepository.class);
        CouponIssueRedisProperties properties = new CouponIssueRedisProperties(Duration.ofDays(7), ZoneId.of("Asia/Seoul"));

        couponStateService = new CouponStateServiceImpl(
                couponIssueRepository,
                couponHistoryRepository,
                properties
        );
    }

    private CouponIssue createTestCoupon(CouponIssueStatus initialStatus) {
        return createTestCoupon(initialStatus, null, null, null);
    }

    private CouponIssue createTestCoupon(CouponIssueStatus initialStatus, LocalDateTime validFrom, LocalDateTime validTo, LocalDateTime usedAt) {
        User user = Mockito.mock(User.class);
        given(user.getId()).willReturn(USER_ID);

        CouponEvent event = Mockito.mock(CouponEvent.class);
        given(event.getId()).willReturn(EVENT_ID);

        return CouponIssue.builder()
                .id(ISSUE_ID)
                .user(user)
                .couponEvent(event)
                .status(initialStatus)
                .validFrom(validFrom != null ? validFrom : LocalDateTime.now().minusDays(1))
                .validTo(validTo != null ? validTo : LocalDateTime.now().plusDays(7))
                .usedAt(usedAt)
                .build();
    }

    @Test
    @DisplayName("[사용] ISSUED 상태의 쿠폰을 사용하면 USED 상태로 변경")
    void useCoupon_success() {
        CouponIssue coupon = createTestCoupon(CouponIssueStatus.ISSUED);
        given(couponIssueRepository.findByIdForUpdate(ISSUE_ID)).willReturn(Optional.of(coupon));
        given(couponHistoryRepository.findByEventUid(any())).willReturn(Optional.empty());

        CouponStateChangeResponse response = couponStateService.use(ISSUE_ID, USER_ID, UUID.randomUUID(), "결제 사용");

        assertThat(coupon.getStatus()).isEqualTo(CouponIssueStatus.USED);
        assertThat(response.previousStatus()).isEqualTo(CouponIssueStatus.ISSUED);
        assertThat(response.currentStatus()).isEqualTo(CouponIssueStatus.USED);
        verify(couponHistoryRepository, times(1)).save(any(CouponHistory.class));
    }

    @Test
    @DisplayName("[취소 및 원상복구] 실제 usedAt이 설정된 USED 쿠폰을 취소하면 status는 ISSUED가 되고 usedAt은 null로 초기화된다")
    void cancelCoupon_success_restoreToIssued_resetsUsedAt() {
        LocalDateTime actualUsedTime = LocalDateTime.now().minusHours(2);
        CouponIssue coupon = createTestCoupon(CouponIssueStatus.USED, null, null, actualUsedTime);
        given(couponIssueRepository.findByIdForUpdate(ISSUE_ID)).willReturn(Optional.of(coupon));
        given(couponHistoryRepository.findByEventUid(any())).willReturn(Optional.empty());

        CouponStateChangeResponse response = couponStateService.cancel(ISSUE_ID, USER_ID, UUID.randomUUID(), "주문 취소");

        assertThat(coupon.getStatus()).isEqualTo(CouponIssueStatus.ISSUED);
        assertThat(coupon.getUsedAt()).isNull(); // usedAt null 초기화 검증
        assertThat(coupon.getCanceledAt()).isNotNull();
        assertThat(response.previousStatus()).isEqualTo(CouponIssueStatus.USED);
        assertThat(response.currentStatus()).isEqualTo(CouponIssueStatus.ISSUED);
        verify(couponHistoryRepository, times(1)).save(any(CouponHistory.class));
    }

    @Test
    @DisplayName("[사용 -> 취소 -> 재사용] 전체 생명주기 검증")
    void fullLifecycle_use_cancel_reuse() {
        CouponIssue coupon = createTestCoupon(CouponIssueStatus.ISSUED);
        given(couponIssueRepository.findByIdForUpdate(ISSUE_ID)).willReturn(Optional.of(coupon));
        given(couponHistoryRepository.findByEventUid(any())).willReturn(Optional.empty());

        // 1) 사용
        couponStateService.use(ISSUE_ID, USER_ID, UUID.randomUUID(), "1차 결제");
        assertThat(coupon.getStatus()).isEqualTo(CouponIssueStatus.USED);

        // 2) 취소 (원복)
        couponStateService.cancel(ISSUE_ID, USER_ID, UUID.randomUUID(), "결제 취소");
        assertThat(coupon.getStatus()).isEqualTo(CouponIssueStatus.ISSUED);
        assertThat(coupon.getUsedAt()).isNull();

        // 3) 재사용
        couponStateService.use(ISSUE_ID, USER_ID, UUID.randomUUID(), "2차 재결제");
        assertThat(coupon.getStatus()).isEqualTo(CouponIssueStatus.USED);

        verify(couponHistoryRepository, times(3)).save(any(CouponHistory.class));
    }

    @Test
    @DisplayName("[예외 방어] 미사용(ISSUED) 상태에서 취소를 시도하면 NOT_YET_USED 예외가 발생")
    void cancelCoupon_notYetUsed_throwsException() {
        CouponIssue coupon = createTestCoupon(CouponIssueStatus.ISSUED);
        given(couponIssueRepository.findByIdForUpdate(ISSUE_ID)).willReturn(Optional.of(coupon));
        given(couponHistoryRepository.findByEventUid(any())).willReturn(Optional.empty());

        assertThatThrownBy(() -> couponStateService.cancel(ISSUE_ID, USER_ID, UUID.randomUUID(), "취소 시도"))
                .isInstanceOf(CouponException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_YET_USED);
    }

    @Test
    @DisplayName("[멱등성] 동일한 Idempotency-Key로 재요청 시 최초 성공 응답(200 OK)과 동일한 결과를 반환한다")
    void retryWithSameKey_returnsOriginalResponse() {
        UUID idempotencyKey = UUID.randomUUID();
        LocalDateTime firstOccurredAt = LocalDateTime.now().minusMinutes(5);

        CouponIssue coupon = createTestCoupon(CouponIssueStatus.USED, null, null, firstOccurredAt);
        CouponHistory existingHistory = CouponHistory.builder()
                .couponIssue(coupon)
                .fromStatus(CouponIssueStatus.ISSUED)
                .toStatus(CouponIssueStatus.USED)
                .actor("USER_" + USER_ID)
                .occurredAt(firstOccurredAt)
                .eventUid(idempotencyKey.toString())
                .build();

        given(couponHistoryRepository.findByEventUid(idempotencyKey.toString()))
                .willReturn(Optional.of(existingHistory));

        CouponStateChangeResponse retryResponse = couponStateService.use(ISSUE_ID, USER_ID, idempotencyKey, "재시도");

        assertThat(retryResponse.requestId()).isEqualTo(idempotencyKey);
        assertThat(retryResponse.currentStatus()).isEqualTo(CouponIssueStatus.USED);
        assertThat(retryResponse.changedAt()).isEqualTo(firstOccurredAt);
        verify(couponIssueRepository, Mockito.never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("[유효기간] 만료된 쿠폰을 취소하려고 하면 ALREADY_EXPIRED 예외가 발생한다")
    void cancelAfterValidTo_throwsException() {
        LocalDateTime pastValidTo = LocalDateTime.now().minusDays(1);
        CouponIssue coupon = createTestCoupon(CouponIssueStatus.USED, LocalDateTime.now().minusDays(7), pastValidTo, LocalDateTime.now().minusDays(2));

        given(couponIssueRepository.findByIdForUpdate(ISSUE_ID)).willReturn(Optional.of(coupon));
        given(couponHistoryRepository.findByEventUid(any())).willReturn(Optional.empty());

        assertThatThrownBy(() -> couponStateService.cancel(ISSUE_ID, USER_ID, UUID.randomUUID(), "만료 후 취소"))
                .isInstanceOf(CouponException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ALREADY_EXPIRED);
    }
}
