package com.ace.common.api;

import java.time.OffsetDateTime;
import java.util.List;

public record ErrorResponse(
        String code,
        String message,
        String path,
        OffsetDateTime timestamp,
        List<FieldErrorDetail> errors
) {

    public static ErrorResponse of(
            String code,
            String message,
            String path
    ) {
        return new ErrorResponse(
                code,
                message,
                path,
                OffsetDateTime.now(),
                List.of()
        );
    }

    public static ErrorResponse of(
            String code,
            String message,
            String path,
            List<FieldErrorDetail> errors
    ) {
        return new ErrorResponse(
                code,
                message,
                path,
                OffsetDateTime.now(),
                errors
        );
    }

    public record FieldErrorDetail(
            String field,
            String message
    ) {
    }
}