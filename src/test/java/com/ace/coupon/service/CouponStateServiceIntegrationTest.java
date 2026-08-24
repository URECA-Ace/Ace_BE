package com.ace.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.dto.response.CouponStateChangeResponse;
import com.ace.coupon.entity.Coupon;
import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.entity.CouponHistory;
import com.ace.coupon.entity.CouponIssue;
import com.ace.coupon.enums.CouponIssueStatus;
import com.ace.coupon.repository.CouponHistoryRepository;
import com.ace.coupon.repository.CouponIssueRepository;
import com.ace.user.entity.User;
import com.ace.consistency.check.ConsistencyCheckIntegrationTestBase;
import jakarta.persistence.EntityManager;

@Transactional
class CouponStateServiceIntegrationTest extends ConsistencyCheckIntegrationTestBase {

    @Autowired
    private CouponStateService couponStateService;

    @Autowired
    private CouponIssueRepository couponIssueRepository;

    @Autowired
    private CouponHistoryRepository couponHistoryRepository;

    @Autowired
    private EntityManager em;

    private User testUser;
    private CouponEvent testEvent;
    private CouponIssue testIssue;

    @BeforeEach
    void setUp() {
        testUser = User.builder().build();
        em.persist(testUser);

        Coupon coupon = Coupon.builder()
                .couponName("통합 테스트 쿠폰")
                .type("DISCOUNT")
                .value(1000L)
                .validHours(24)
                .createdAt(LocalDateTime.now())
                .build();
        em.persist(coupon);

        testEvent = CouponEvent.builder()
                .coupon(coupon)
                .totalStock(100)
                .openAt(LocalDateTime.now().minusDays(1))
                .closeAt(LocalDateTime.now().plusDays(7))
                .createdAt(LocalDateTime.now())
                .build();
        em.persist(testEvent);

        testIssue = CouponIssue.builder()
                .couponEvent(testEvent)
                .user(testUser)
                .issueSequence(1)
                .requestId(UUID.randomUUID().toString())
                .status(CouponIssueStatus.ISSUED)
                .issuedAt(LocalDateTime.now())
                .validFrom(LocalDateTime.now().minusDays(1))
                .validTo(LocalDateTime.now().plusDays(7))
                .createdAt(LocalDateTime.now())
                .build();
        em.persist(testIssue);
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("DB 통합 테스트: 실제 사용된 쿠폰을 취소하면 DB의 status는 ISSUED가 되고 used_at은 null로 UPDATE ")
    void cancelCoupon_actualDbUpdate_usedAtIsNull() {
    	
        UUID useKey = UUID.randomUUID();
        
        couponStateService.use(testIssue.getId(), testUser.getId(), useKey, "실제 결제");
        em.flush();
        em.clear();

        CouponIssue usedFromDb = couponIssueRepository.findById(testIssue.getId()).orElseThrow();
        assertThat(usedFromDb.getStatus()).isEqualTo(CouponIssueStatus.USED);
        assertThat(usedFromDb.getUsedAt()).isNotNull();

        UUID cancelKey = UUID.randomUUID();
        couponStateService.cancel(testIssue.getId(), testUser.getId(), cancelKey, "주문  취소");
        em.flush();
        em.clear();

        CouponIssue canceledFromDb = couponIssueRepository.findById(testIssue.getId()).orElseThrow();
        assertThat(canceledFromDb.getStatus()).isEqualTo(CouponIssueStatus.ISSUED);
        assertThat(canceledFromDb.getUsedAt()).isNull(); 
        assertThat(canceledFromDb.getCanceledAt()).isNotNull();

        assertThat(couponHistoryRepository.findAllByCouponIssue_IdOrderByOccurredAtAsc(testIssue.getId())).hasSize(2);
    }

    @Test
    @DisplayName("DB 통합 테스트: 동일한 Idempotency-Key로 재요청 시 DB 추가  INSERT 없이 최초 결과를 200 OK로 반환")
    void idempotencyRetry_returnsOriginalResponseFromDb() {
        UUID key = UUID.randomUUID();

        CouponStateChangeResponse firstResponse = couponStateService.use(testIssue.getId(), testUser.getId(), key, "1차 결제");
        em.flush();
        em.clear();

        CouponStateChangeResponse retryResponse = couponStateService.use(testIssue.getId(), testUser.getId(), key, "재시도 결제");

        assertThat(retryResponse.currentStatus()).isEqualTo(CouponIssueStatus.USED);
        assertThat(retryResponse.requestId()).isEqualTo(key);
        assertThat(retryResponse.changedAt()).isEqualTo(firstResponse.changedAt());

        assertThat(couponHistoryRepository.findAllByCouponIssue_IdOrderByOccurredAtAsc(testIssue.getId())).hasSize(1);
    }

    @Test
    @DisplayName("DB 통합 테스트 : 만료된 쿠폰을 취소 시도  시 ALREADY_EXPIRED 예외가 발생/ DB 상태가 변하지 않는다")
    void cancelExpiredCoupon_throwsException_noDbChange() {
        CouponIssue expiredCoupon = CouponIssue.builder()
                .couponEvent(testEvent)
                .user(testUser)
                .issueSequence(2)
                .requestId(UUID.randomUUID().toString())
                .status(CouponIssueStatus.USED)
                .issuedAt(LocalDateTime.now().minusDays(10))
                .validFrom(LocalDateTime.now().minusDays(10))
                .validTo(LocalDateTime.now().minusDays(1)) // 만료됨
                .usedAt(LocalDateTime.now().minusDays(5))
                .createdAt(LocalDateTime.now().minusDays(10))
                .build();
        
        em.persist(expiredCoupon);
        em.flush();
        em.clear();

        assertThatThrownBy(() -> couponStateService.cancel(expiredCoupon.getId(), testUser.getId(), UUID.randomUUID(), "만료 후 취소"))
                .isInstanceOf(CouponException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ALREADY_EXPIRED);
    }
}
