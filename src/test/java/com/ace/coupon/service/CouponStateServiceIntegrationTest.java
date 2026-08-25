package com.ace.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.dto.response.CouponStateChangeResponse;
import com.ace.coupon.entity.Coupon;
import com.ace.coupon.entity.CouponEvent;
import com.ace.coupon.entity.CouponIssue;
import com.ace.coupon.enums.CouponEventStatus;
import com.ace.coupon.enums.CouponIssueStatus;
import com.ace.coupon.repository.CouponHistoryRepository;
import com.ace.coupon.repository.CouponIssueRepository;
import com.ace.coupon.repository.CouponStateIdempotencyRepository;
import com.ace.user.entity.User;
import com.ace.consistency.check.ConsistencyCheckIntegrationTestBase;
import jakarta.persistence.EntityManager;

class CouponStateServiceIntegrationTest extends ConsistencyCheckIntegrationTestBase {

    @Autowired private CouponStateService couponStateService;
    @Autowired private CouponIssueRepository couponIssueRepository;
    @Autowired private CouponHistoryRepository couponHistoryRepository;
    @Autowired private CouponStateIdempotencyRepository idempotencyRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private EntityManager em;

    private Long testUserId;
    private Long testIssueId;
    private Long testEventId;

    @BeforeEach
    void setUp() {
        transactionTemplate.executeWithoutResult(status -> {
            User user = User.builder()
                    .email("test@ace.com")
                    .name("테스터")
                    .createdAt(LocalDateTime.now())
                    .build();
            em.persist(user);

            Coupon coupon = Coupon.builder()
                    .couponName("통합 테스트 쿠폰")
                    .type("DISCOUNT")
                    .value(1000L)
                    .validHours(24)
                    .createdAt(LocalDateTime.now())
                    .build();
            em.persist(coupon);

            CouponEvent event = CouponEvent.builder()
                    .coupon(coupon)
                    .round(1)
                    .totalStock(100)
                    .remainingStock(100)
                    .issuedQuantity(0)
                    .perUserLimit(1)
                    .status(CouponEventStatus.OPEN)
                    .openAt(LocalDateTime.now().minusDays(1))
                    .closeAt(LocalDateTime.now().plusDays(7))
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            em.persist(event);

            CouponIssue issue = CouponIssue.builder()
                    .couponEvent(event)
                    .user(user)
                    .issueSequence(1)
                    .requestId(UUID.randomUUID().toString())
                    .status(CouponIssueStatus.ISSUED)
                    .issuedAt(LocalDateTime.now())
                    .validFrom(LocalDateTime.now().minusDays(1))
                    .validTo(LocalDateTime.now().plusDays(7))
                    .createdAt(LocalDateTime.now())
                    .build();
            em.persist(issue);

            testUserId = user.getId();
            testIssueId = issue.getId();
            testEventId = event.getId();
        });
    }

    @Override
    @AfterEach
    protected void tearDown() {
        transactionTemplate.executeWithoutResult(status -> {
            idempotencyRepository.deleteAll();
            couponHistoryRepository.deleteAll();
            couponIssueRepository.deleteAll();
            em.createNativeQuery("DELETE FROM coupon_event").executeUpdate();
            em.createNativeQuery("DELETE FROM coupon").executeUpdate();
            em.createNativeQuery("DELETE FROM user").executeUpdate();
        });
    }

    @Test
    @DisplayName("실제 사용된 쿠폰을 취소하면 status=ISSUED, usedAt=null, canceledAt≠null")
    void cancelCoupon_actualDbUpdate_usedAtIsNull() {
        UUID useKey = UUID.randomUUID();
        couponStateService.use(testIssueId, testUserId, useKey, "실제 결제");

        UUID cancelKey = UUID.randomUUID();
        couponStateService.cancel(testIssueId, testUserId, cancelKey, "주문 취소");

        transactionTemplate.executeWithoutResult(status -> {
            CouponIssue canceled = couponIssueRepository.findById(testIssueId).orElseThrow();
            assertThat(canceled.getStatus()).isEqualTo(CouponIssueStatus.ISSUED);
            assertThat(canceled.getUsedAt()).isNull();
            assertThat(canceled.getCanceledAt()).isNotNull();
            assertThat(couponHistoryRepository
                    .findAllByCouponIssue_IdOrderByOccurredAtAsc(testIssueId)).hasSize(2);
        });
    }

    @Test
    @DisplayName("동일 키로 순차 재요청 시 최초 응답을 200 OK로 복원한다")
    void idempotencyRetry_sequential_returnsOriginalResponse() {
        UUID key = UUID.randomUUID();

        CouponStateChangeResponse first =
                couponStateService.use(testIssueId, testUserId, key, "1차 결제");
        CouponStateChangeResponse retry =
                couponStateService.use(testIssueId, testUserId, key, "재시도");

        assertThat(retry.currentStatus()).isEqualTo(CouponIssueStatus.USED);
        assertThat(retry.requestId()).isEqualTo(key);
        assertThat(retry.changedAt()).isNotNull();
    }

    @Test
    @DisplayName("만료된 쿠폰 취소 시 ALREADY_EXPIRED 예외 발생")
    void cancelExpiredCoupon_throwsAlreadyExpired() {
        Long expiredIssueId = transactionTemplate.execute(status -> {
            User user2 = User.builder()
                    .email("expired@ace.com")
                    .name("만료테스터")
                    .createdAt(LocalDateTime.now())
                    .build();
            em.persist(user2);

            CouponEvent event = em.find(CouponEvent.class, testEventId);
            CouponIssue expired = CouponIssue.builder()
                    .couponEvent(event)
                    .user(user2)
                    .issueSequence(2)
                    .requestId(UUID.randomUUID().toString())
                    .status(CouponIssueStatus.USED)
                    .issuedAt(LocalDateTime.now().minusDays(10))
                    .validFrom(LocalDateTime.now().minusDays(10))
                    .validTo(LocalDateTime.now().minusDays(1))
                    .usedAt(LocalDateTime.now().minusDays(5))
                    .createdAt(LocalDateTime.now().minusDays(10))
                    .build();
            em.persist(expired);
            return expired.getId();
        });

        Long user2Id = transactionTemplate.execute(status -> {
            CouponIssue issue = couponIssueRepository.findById(expiredIssueId).orElseThrow();
            return issue.getUser().getId();
        });

        assertThatThrownBy(() ->
                couponStateService.cancel(expiredIssueId, user2Id, UUID.randomUUID(), "만료 후 취소"))
                .isInstanceOf(CouponException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ALREADY_EXPIRED);
    }

    @Test
    @DisplayName("validTo가 현재 시각보다 과거인 쿠폰은 사용 불가 (만료 경계값)")
    void useCoupon_expiredValidTo_throwsAlreadyExpired() {
        Long expiredIssueId = transactionTemplate.execute(status -> {
            User user3 = User.builder()
                    .email("boundary@ace.com")
                    .name("경계테스터")
                    .createdAt(LocalDateTime.now())
                    .build();
            em.persist(user3);

            CouponEvent event = em.find(CouponEvent.class, testEventId);
            CouponIssue expired = CouponIssue.builder()
                    .couponEvent(event)
                    .user(user3)
                    .issueSequence(3)
                    .requestId(UUID.randomUUID().toString())
                    .status(CouponIssueStatus.ISSUED)
                    .issuedAt(LocalDateTime.now().minusDays(10))
                    .validFrom(LocalDateTime.now().minusDays(10))
                    .validTo(LocalDateTime.now().minusSeconds(1))
                    .createdAt(LocalDateTime.now().minusDays(10))
                    .build();
            em.persist(expired);
            return expired.getId();
        });

        Long user3Id = transactionTemplate.execute(status -> {
            CouponIssue issue = couponIssueRepository.findById(expiredIssueId).orElseThrow();
            return issue.getUser().getId();
        });

        assertThatThrownBy(() ->
                couponStateService.use(expiredIssueId, user3Id, UUID.randomUUID(), "만료 경계 사용"))
                .isInstanceOf(CouponException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ALREADY_EXPIRED);
    }

    @Test
    @DisplayName("서로 다른 키로 동일 쿠폰 동시 사용 → 1건 성공, 나머지 ALREADY_USED")
    void concurrent_differentKey_sameCoupon_pessimisticLock() throws Exception {
        int threadCount = 3;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        List<Future<Object>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            UUID uniqueKey = UUID.randomUUID();
            futures.add(executor.submit(() -> {
                ready.countDown();
                ready.await();
                try {
                    return couponStateService.use(testIssueId, testUserId, uniqueKey, "동시 사용");
                } catch (CouponException e) {
                    return e;
                }
            }));
        }

        List<CouponStateChangeResponse> ok = new ArrayList<>();
        List<CouponException> err = new ArrayList<>();
        for (Future<Object> f : futures) {
            Object r = f.get(10, TimeUnit.SECONDS);
            if (r instanceof CouponStateChangeResponse res) ok.add(res);
            else if (r instanceof CouponException ex) err.add(ex);
        }

        assertThat(ok).hasSize(1);
        assertThat(err).hasSize(2);
        for (CouponException ex : err) {
            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ALREADY_USED);
        }

        transactionTemplate.executeWithoutResult(status -> {
            assertThat(couponHistoryRepository
                    .findAllByCouponIssue_IdOrderByOccurredAtAsc(testIssueId)).hasSize(1);
        });

        executor.shutdown();
    }

    @Test
    @DisplayName("같은 키·같은 요청 동시 진입 → 둘 다 200 OK")
    void concurrent_sameKey_sameRequest_bothReturn200() throws Exception {
        UUID sharedKey = UUID.randomUUID();
        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        List<Future<Object>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                ready.await();
                try {
                    return couponStateService.use(testIssueId, testUserId, sharedKey, "따닥");
                } catch (CouponException e) {
                    return e;
                }
            }));
        }

        List<CouponStateChangeResponse> ok = new ArrayList<>();
        List<CouponException> err = new ArrayList<>();
        for (Future<Object> f : futures) {
            Object r = f.get(10, TimeUnit.SECONDS);
            if (r instanceof CouponStateChangeResponse res) ok.add(res);
            else if (r instanceof CouponException ex) err.add(ex);
        }

        assertThat(ok).hasSize(2);
        assertThat(err).isEmpty();

        for (CouponStateChangeResponse res : ok) {
            assertThat(res.currentStatus()).isEqualTo(CouponIssueStatus.USED);
            assertThat(res.requestId()).isEqualTo(sharedKey);
        }

        transactionTemplate.executeWithoutResult(status -> {
            assertThat(couponHistoryRepository
                    .findAllByCouponIssue_IdOrderByOccurredAtAsc(testIssueId)).hasSize(1);
            assertThat(idempotencyRepository.findByEventUid(sharedKey.toString())).isPresent();
        });

        executor.shutdown();
    }

    @Test
    @DisplayName("같은 키 + 다른 issueId → 1건 성공, 나머지 409 IDEMPOTENCY_CONFLICT")
    void concurrent_sameKey_differentFingerprint_throws409() throws Exception {
        Long secondIssueId = transactionTemplate.execute(status -> {
            User user2 = User.builder()
                    .email("second@ace.com")
                    .name("두번째테스터")
                    .createdAt(LocalDateTime.now())
                    .build();
            em.persist(user2);

            CouponEvent event = em.find(CouponEvent.class, testEventId);
            CouponIssue issue = CouponIssue.builder()
                    .couponEvent(event)
                    .user(user2)
                    .issueSequence(99)
                    .requestId(UUID.randomUUID().toString())
                    .status(CouponIssueStatus.ISSUED)
                    .issuedAt(LocalDateTime.now())
                    .validFrom(LocalDateTime.now().minusDays(1))
                    .validTo(LocalDateTime.now().plusDays(7))
                    .createdAt(LocalDateTime.now())
                    .build();
            em.persist(issue);
            return issue.getId();
        });

        Long user2Id = transactionTemplate.execute(status -> {
            return couponIssueRepository.findById(secondIssueId).orElseThrow().getUser().getId();
        });

        UUID sharedKey = UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);

        Future<Object> f1 = executor.submit(() -> {
            ready.countDown();
            ready.await();
            try {
                return couponStateService.use(testIssueId, testUserId, sharedKey, "사용 1");
            } catch (CouponException e) {
                return e;
            }
        });

        Future<Object> f2 = executor.submit(() -> {
            ready.countDown();
            ready.await();
            try {
                return couponStateService.use(secondIssueId, user2Id, sharedKey, "사용 2");
            } catch (CouponException e) {
                return e;
            }
        });

        Object res1 = f1.get(10, TimeUnit.SECONDS);
        Object res2 = f2.get(10, TimeUnit.SECONDS);

        List<Object> results = List.of(res1, res2);
        long successCount = results.stream()
                .filter(r -> r instanceof CouponStateChangeResponse).count();
        long conflictCount = results.stream()
                .filter(r -> r instanceof CouponException ex
                        && ex.getErrorCode() == ErrorCode.IDEMPOTENCY_CONFLICT)
                .count();

        assertThat(successCount).isEqualTo(1);
        assertThat(conflictCount).isEqualTo(1);
        executor.shutdown();
    }
}
