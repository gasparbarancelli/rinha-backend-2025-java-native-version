package com.gasparbarancelli.entity;

import java.math.BigDecimal;

public record PaymentSummary(
        ProcessorSummary defaultProcessor,
        ProcessorSummary fallback
) {
    public record ProcessorSummary(
            long totalRequests,
            BigDecimal totalAmount
    ) {}
}