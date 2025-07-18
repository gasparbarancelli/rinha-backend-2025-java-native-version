package com.gasparbarancelli.transport.model;

public record ServiceHealthRequest(boolean failing, int minResponseTime) {}