package com.ace.coupon.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.ace.coupon.dto.response.CouponStateChangeResponse;
import com.ace.coupon.enums.CouponIssueStatus;
import com.ace.coupon.service.CouponStateService;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

@WebMvcTest(CouponStateController.class)
class CouponStateControllerTest {

    @Autowired private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @MockitoBean private CouponStateService couponStateService;

    @Test
    @DisplayName("Idempotency-Key 헤더가 누락되면 400 에러를 반환한다")
    void missingIdempotencyKey_returns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("userId", 1L, "reason", "결제"));

        mockMvc.perform(patch("/api/v1/coupons/1/use")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Idempotency-Key가 UUID 형식이 아니면 400 에러를 반환한다")
    void invalidIdempotencyKey_returns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("userId", 1L, "reason", "결제"));

        mockMvc.perform(patch("/api/v1/coupons/1/use")
                .header("Idempotency-Key", "not-a-uuid")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("issueId가 0 이하면 400 에러를 반환한다")
    void nonPositiveIssueId_returns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("userId", 1L, "reason", "결제"));

        mockMvc.perform(patch("/api/v1/coupons/-1/use")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("userId가 누락되면 400 에러를 반환한다")
    void missingUserId_returns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("reason", "결제"));

        mockMvc.perform(patch("/api/v1/coupons/1/use")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("수동 만료 요청이 정상 처리되면 200 OK를 반환한다")
    void expire_success_returns200() throws Exception {
        UUID idempotencyKey = UUID.randomUUID();
        LocalDateTime changedAt = LocalDateTime.of(2026, 8, 28, 12, 0);
        CouponStateChangeResponse response = new CouponStateChangeResponse(
                idempotencyKey, 1L, 10L, 100L,
                CouponIssueStatus.ISSUED, CouponIssueStatus.EXPIRED, changedAt);
        given(couponStateService.expire(
                eq(1L), eq(100L), eq(idempotencyKey), eq("운영자 수동 만료")))
                .willReturn(response);
        String body = objectMapper.writeValueAsString(
                Map.of("userId", 100L, "reason", "운영자 수동 만료"));

        mockMvc.perform(patch("/api/v1/coupons/1/expire")
                .header("Idempotency-Key", idempotencyKey.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.issueId").value(1L))
                .andExpect(jsonPath("$.data.currentStatus").value("EXPIRED"));

        verify(couponStateService).expire(1L, 100L, idempotencyKey, "운영자 수동 만료");
    }
}
