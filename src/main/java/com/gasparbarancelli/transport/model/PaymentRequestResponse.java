package com.gasparbarancelli.transport.model;

import java.math.BigDecimal;

public record PaymentRequestResponse(
        String correlationId,
        BigDecimal amount,
        String requestedAt
) {
}