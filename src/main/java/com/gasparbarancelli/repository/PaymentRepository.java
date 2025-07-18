package com.gasparbarancelli.repository;

import com.gasparbarancelli.entity.Payment;
import com.gasparbarancelli.entity.PaymentSummary;
import com.gasparbarancelli.entity.ProcessorService;

import java.time.Instant;

public interface PaymentRepository {

    void enqueuePayment(Payment request);

    Payment dequeuePayment(int workerId);

    void requeuePayment(Payment request);

    void savePayment(Payment request, ProcessorService service);

    boolean acquireHealthCheckLock();

    void releaseHealthCheckLock();

    void storeHealthyProcessor(ProcessorService service);

    ProcessorService getHealthyProcessor();

    PaymentSummary getPaymentsSummary(Instant from, Instant to);

    void purgeAllData();

}