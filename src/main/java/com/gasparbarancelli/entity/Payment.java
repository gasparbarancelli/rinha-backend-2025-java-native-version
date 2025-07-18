package com.gasparbarancelli.entity;

import java.math.BigDecimal;

public record Payment(String correlationId, BigDecimal amount) {
    public Payment {
        if (correlationId == null) {
            throw new IllegalArgumentException("correlationId cannot be null");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
}