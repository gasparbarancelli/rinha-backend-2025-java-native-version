package com.gasparbarancelli.transport.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

public record PaymentSummaryResponse(
        @JsonProperty("default") ProcessorSummaryResponse defaultProcessor,
        ProcessorSummaryResponse fallback
) {
    public record ProcessorSummaryResponse(
            long totalRequests,
            BigDecimal totalAmount
    ) {
    }
}