package com.ace.common.api;

public record ApiResponse<T>(
        String code,
        String message,
        T data
) {

    public static <T> ApiResponse<T> success(
            String code,
            String message,
            T data
    ) {
        return new ApiResponse<>(code, message, data);
    }
}