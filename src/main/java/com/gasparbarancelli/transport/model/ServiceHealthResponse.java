package com.gasparbarancelli.transport.model;

public record ServiceHealthResponse(boolean failing, int minResponseTime) {
}