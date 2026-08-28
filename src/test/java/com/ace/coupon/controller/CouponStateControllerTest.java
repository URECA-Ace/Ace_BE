package com.ace.coupon.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

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
        String body = objectMapper.writeValueAsString(Map.of("userId", 1L, "reason", "MANUAL_EXPIRED"));

        mockMvc.perform(patch("/api/v1/coupons/1/expire")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk());
    }
}
