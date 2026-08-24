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
        User user = Mockito.mock(User.class);
        given(user.getId()).willReturn(USER_ID);

        CouponEvent event = Mockito.mock(CouponEvent.class);
        given(event.getId()).willReturn(EVENT_ID);

        return CouponIssue.builder()
                .id(ISSUE_ID)
                .user(user)
                .couponEvent(event)
                .status(initialStatus)
                .validFrom(LocalDateTime.now().minusDays(1))
                .validTo(LocalDateTime.now().plusDays(7))
                .build();
    }

    @Test
    @DisplayName("[사용] ISSUED 상태의 쿠폰을 사용하면 USED 상태로 변경")
    void useCoupon_success() {
        CouponIssue coupon = createTestCoupon(CouponIssueStatus.ISSUED);
        given(couponIssueRepository.findByIdForUpdate(ISSUE_ID)).willReturn(Optional.of(coupon));
        given(couponHistoryRepository.existsByEventUid(any())).willReturn(false);

        CouponStateChangeResponse response = couponStateService.use(ISSUE_ID, USER_ID, UUID.randomUUID(), "결제 사용");

        assertThat(coupon.getStatus()).isEqualTo(CouponIssueStatus.USED);
        assertThat(response.previousStatus()).isEqualTo(CouponIssueStatus.ISSUED);
        assertThat(response.currentStatus()).isEqualTo(CouponIssueStatus.USED);
        verify(couponHistoryRepository, times(1)).save(any(CouponHistory.class));
    }

    @Test
    @DisplayName("[취소 및 원상복구] USED 상태의 쿠폰을 취소하면 다시 ISSUED(사용 가능) 상태로 원상복구된다")
    void cancelCoupon_success_restoreToIssued() {
        CouponIssue coupon = createTestCoupon(CouponIssueStatus.USED);
        given(couponIssueRepository.findByIdForUpdate(ISSUE_ID)).willReturn(Optional.of(coupon));
        given(couponHistoryRepository.existsByEventUid(any())).willReturn(false);

        CouponStateChangeResponse response = couponStateService.cancel(ISSUE_ID, USER_ID, UUID.randomUUID(), "주문 취소");

        assertThat(coupon.getStatus()).isEqualTo(CouponIssueStatus.ISSUED);
        assertThat(response.previousStatus()).isEqualTo(CouponIssueStatus.USED);
        assertThat(response.currentStatus()).isEqualTo(CouponIssueStatus.ISSUED);
        verify(couponHistoryRepository, times(1)).save(any(CouponHistory.class));
    }

    @Test
    @DisplayName(" [사용 -> 취소 -> 재사용] ")
    void fullLifecycle_use_cancel_reuse() {
        CouponIssue coupon = createTestCoupon(CouponIssueStatus.ISSUED);
        given(couponIssueRepository.findByIdForUpdate(ISSUE_ID)).willReturn(Optional.of(coupon));
        given(couponHistoryRepository.existsByEventUid(any())).willReturn(false);

        // 1) 사용
        couponStateService.use(ISSUE_ID, USER_ID, UUID.randomUUID(), "1차 결제");
        assertThat(coupon.getStatus()).isEqualTo(CouponIssueStatus.USED);

        // 2) 취소 (원복)
        couponStateService.cancel(ISSUE_ID, USER_ID, UUID.randomUUID(), "결제 취소");
        assertThat(coupon.getStatus()).isEqualTo(CouponIssueStatus.ISSUED);

        // 3) 재사용
        couponStateService.use(ISSUE_ID, USER_ID, UUID.randomUUID(), "2차 재결제");
        assertThat(coupon.getStatus()).isEqualTo(CouponIssueStatus.USED);

        verify(couponHistoryRepository, times(3)).save(any(CouponHistory.class));
    }

    @Test
    @DisplayName("[예외 방어] 미사용(ISSUED) 상태에서 취소를 시도하면 NOT_YET_USED 예외가 발생 ")
    void cancelCoupon_notYetUsed_throwsException() {
        CouponIssue coupon = createTestCoupon(CouponIssueStatus.ISSUED);
        given(couponIssueRepository.findByIdForUpdate(ISSUE_ID)).willReturn(Optional.of(coupon));
        given(couponHistoryRepository.existsByEventUid(any())).willReturn(false);

        assertThatThrownBy(() -> couponStateService.cancel(ISSUE_ID, USER_ID, UUID.randomUUID(), "취소 시도"))
                .isInstanceOf(CouponException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_YET_USED);
    }
}
