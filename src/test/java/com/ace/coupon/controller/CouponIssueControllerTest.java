package com.ace.coupon.controller;

import com.ace.common.ErrorCode;
import com.ace.common.exception.CouponException;
import com.ace.coupon.dto.response.CouponIssueAcceptedResponse;
import com.ace.coupon.enums.IssueRequestStatus;
import com.ace.coupon.service.CouponIssueService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CouponIssueController.class)
class CouponIssueControllerTest {

    private static final Long EVENT_ID = 1L;
    private static final Long USER_ID = 12345L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CouponIssueService couponIssueService;

    @Test
    @DisplayName("발급 판정에 성공하면 202와 요청 정보를 반환한다")
    void issueCouponSuccess() throws Exception {
        // given
        UUID idempotencyKey = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();

        OffsetDateTime acceptedAt =
                OffsetDateTime.parse(
                        "2026-08-13T15:30:00.123456+09:00"
                );

        CouponIssueAcceptedResponse serviceResponse =
                new CouponIssueAcceptedResponse(
                        requestId,
                        EVENT_ID,
                        USER_ID,
                        8271L,
                        1729L,
                        IssueRequestStatus.ACCEPTED,
                        acceptedAt
                );

        given(couponIssueService.issue(
                eq(EVENT_ID),
                eq(USER_ID),
                eq(idempotencyKey)
        )).willReturn(serviceResponse);

        // when & then
        mockMvc.perform(
                        post(
                                "/api/v1/events/{eventId}/issues",
                                EVENT_ID
                        )
                                .header(
                                        "Idempotency-Key",
                                        idempotencyKey.toString()
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "userId": 12345
                                        }
                                        """)
                )
                .andExpect(status().isAccepted())
                .andExpect(header().string(
                        "Location",
                        "/api/v1/issue-requests/" + requestId
                ))
                .andExpect(jsonPath("$.result")
                        .value("success"))
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.data.requestId")
                        .value(requestId.toString()))
                .andExpect(jsonPath("$.data.eventId")
                        .value(EVENT_ID))
                .andExpect(jsonPath("$.data.userId")
                        .value(USER_ID))
                .andExpect(jsonPath("$.data.issueSequence")
                        .value(8271))
                .andExpect(jsonPath("$.data.remainingStock")
                        .value(1729))
                .andExpect(jsonPath("$.data.status")
                        .value("ACCEPTED"))
                .andExpect(jsonPath("$.data.acceptedAt")
                        .value(acceptedAt.toString()));

        verify(couponIssueService).issue(
                EVENT_ID,
                USER_ID,
                idempotencyKey
        );
    }

    @Test
    @DisplayName("Idempotency-Key 헤더가 없으면 400을 반환한다")
    void missingIdempotencyKey() throws Exception {
        mockMvc.perform(
                        post(
                                "/api/v1/events/{eventId}/issues",
                                EVENT_ID
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "userId": 12345
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("MISSING_IDEMPOTENCY_KEY"))
                .andExpect(jsonPath("$.path")
                        .value("/api/v1/events/1/issues"));
    }

    @Test
    @DisplayName("Idempotency-Key가 빈 문자열이면 400을 반환한다")
    void blankIdempotencyKey() throws Exception {
        mockMvc.perform(
                        post(
                                "/api/v1/events/{eventId}/issues",
                                EVENT_ID
                        )
                                .header("Idempotency-Key", " ")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "userId": 12345
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("MISSING_IDEMPOTENCY_KEY"));
    }

    @Test
    @DisplayName("Idempotency-Key가 UUID 형식이 아니면 400을 반환한다")
    void invalidIdempotencyKey() throws Exception {
        mockMvc.perform(
                        post(
                                "/api/v1/events/{eventId}/issues",
                                EVENT_ID
                        )
                                .header(
                                        "Idempotency-Key",
                                        "not-a-uuid"
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "userId": 12345
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_IDEMPOTENCY_KEY"));
    }

    @Test
    @DisplayName("userId가 없으면 400을 반환한다")
    void missingUserId() throws Exception {
        mockMvc.perform(
                        post(
                                "/api/v1/events/{eventId}/issues",
                                EVENT_ID
                        )
                                .header(
                                        "Idempotency-Key",
                                        UUID.randomUUID().toString()
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.message")
                        .value("userId는 필수입니다."));
    }

    @Test
    @DisplayName("userId가 0이면 400을 반환한다")
    void nonPositiveUserId() throws Exception {
        mockMvc.perform(
                        post(
                                "/api/v1/events/{eventId}/issues",
                                EVENT_ID
                        )
                                .header(
                                        "Idempotency-Key",
                                        UUID.randomUUID().toString()
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "userId": 0
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("요청 본문이 올바른 JSON이 아니면 400을 반환한다")
    void invalidJsonBody() throws Exception {
        mockMvc.perform(
                        post(
                                "/api/v1/events/{eventId}/issues",
                                EVENT_ID
                        )
                                .header(
                                        "Idempotency-Key",
                                        UUID.randomUUID().toString()
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "userId":
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("이벤트가 존재하지 않으면 404를 반환한다")
    void eventNotFound() throws Exception {
        given(couponIssueService.issue(
                eq(EVENT_ID),
                eq(USER_ID),
                any(UUID.class)
        )).willThrow(
                new CouponException(ErrorCode.EVENT_NOT_FOUND)
        );

        performValidRequest()
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code")
                        .value("EVENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("이벤트가 오픈되지 않았으면 409를 반환한다")
    void eventNotOpen() throws Exception {
        given(couponIssueService.issue(
                eq(EVENT_ID),
                eq(USER_ID),
                any(UUID.class)
        )).willThrow(
                new CouponException(ErrorCode.EVENT_NOT_OPEN)
        );

        performValidRequest()
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("EVENT_NOT_OPEN"));
    }

    @Test
    @DisplayName("이미 발급받은 사용자이면 409를 반환한다")
    void duplicatedIssue() throws Exception {
        given(couponIssueService.issue(
                eq(EVENT_ID),
                eq(USER_ID),
                any(UUID.class)
        )).willThrow(
                new CouponException(ErrorCode.ALREADY_ISSUED)
        );

        performValidRequest()
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code")
                        .value("ALREADY_ISSUED"));
    }

    @Test
    @DisplayName("재고가 소진되었으면 409를 반환한다")
    void soldOut() throws Exception {
        given(couponIssueService.issue(
                eq(EVENT_ID),
                eq(USER_ID),
                any(UUID.class)
        )).willThrow(
                new CouponException(ErrorCode.SOLD_OUT)
        );

        performValidRequest()
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code")
                        .value("SOLD_OUT"));
    }

    @Test
    @DisplayName("동일 멱등성 키가 다른 요청에 사용되면 409를 반환한다")
    void idempotencyConflict() throws Exception {
        given(couponIssueService.issue(
                eq(EVENT_ID),
                eq(USER_ID),
                any(UUID.class)
        )).willThrow(
                new CouponException(
                        ErrorCode.IDEMPOTENCY_CONFLICT
                )
        );

        performValidRequest()
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code")
                        .value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    @DisplayName("발급 시스템을 사용할 수 없으면 503을 반환한다")
    void temporarilyUnavailable() throws Exception {
        given(couponIssueService.issue(
                eq(EVENT_ID),
                eq(USER_ID),
                any(UUID.class)
        )).willThrow(
                new CouponException(
                        ErrorCode.ISSUE_TEMPORARILY_UNAVAILABLE
                )
        );

        performValidRequest()
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code")
                        .value("ISSUE_TEMPORARILY_UNAVAILABLE"));
    }

    private org.springframework.test.web.servlet.ResultActions
    performValidRequest() throws Exception {

        return mockMvc.perform(
                post(
                        "/api/v1/events/{eventId}/issues",
                        EVENT_ID
                )
                        .header(
                                "Idempotency-Key",
                                UUID.randomUUID().toString()
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": 12345
                                }
                                """)
        );
    }
}
