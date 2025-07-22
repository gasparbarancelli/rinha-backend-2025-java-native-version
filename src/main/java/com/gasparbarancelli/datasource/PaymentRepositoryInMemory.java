package com.gasparbarancelli.datasource;

import com.gasparbarancelli.entity.Payment;
import com.gasparbarancelli.entity.PaymentSummary;
import com.gasparbarancelli.entity.ProcessorService;
import com.gasparbarancelli.repository.PaymentRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class PaymentRepositoryInMemory implements PaymentRepository {
    private static final int QUEUE_CAPACITY = 100_000;
    private final Payment[] paymentRing;
    private final AtomicInteger head = new AtomicInteger(0);
    private final AtomicInteger tail = new AtomicInteger(0);
    private final Semaphore availableSlots;
    private final Semaphore availableItems;

    private final ConcurrentHashMap<String, PaymentData> paymentsMap;
    private final AtomicBoolean healthCheckLock = new AtomicBoolean(false);
    private final AtomicReference<ProcessorService> healthyProcessor = new AtomicReference<>(ProcessorService.DEFAULT);
    private final ProcessorMetrics defaultMetrics = new ProcessorMetrics();
    private final ProcessorMetrics fallbackMetrics = new ProcessorMetrics();

    private static class PaymentData {
        final String correlationId;
        final long amountCents;
        final boolean isDefault;
        final long timestampMillis;

        PaymentData(String correlationId, BigDecimal amount, boolean isDefault, long timestampMillis) {
            this.correlationId = correlationId;
            this.amountCents = amount.movePointRight(2).longValue();
            this.isDefault = isDefault;
            this.timestampMillis = timestampMillis;
        }
    }

    private static class ProcessorMetrics {
        private final AtomicLong count = new AtomicLong(0);
        private final AtomicLong totalCents = new AtomicLong(0);

        void add(BigDecimal amount) {
            count.incrementAndGet();
            totalCents.addAndGet(amount.movePointRight(2).longValue());
        }

        BigDecimal getTotal() {
            return BigDecimal.valueOf(totalCents.get()).movePointLeft(2);
        }

        long getCount() {
            return count.get();
        }

        void reset() {
            count.set(0);
            totalCents.set(0);
        }
    }

    public PaymentRepositoryInMemory() {
        this.paymentRing = new Payment[QUEUE_CAPACITY];
        this.availableSlots = new Semaphore(QUEUE_CAPACITY);
        this.availableItems = new Semaphore(0);
        this.paymentsMap = new ConcurrentHashMap<>(10_000, 0.75f, 16);
    }

    public void enqueuePayment(Payment request) {
        try {
            if (!availableSlots.tryAcquire()) {
                throw new IllegalStateException("Payment queue is full");
            }
            int index = tail.getAndIncrement() % QUEUE_CAPACITY;
            paymentRing[index] = request;
            availableItems.release();
        } catch (Exception e) {
            availableSlots.release();
            throw new IllegalStateException("Failed to enqueue payment", e);
        }
    }

    public Payment dequeuePayment(int workerId) {
        try {
            availableItems.acquire(); // Bloqueia até ter item disponível
            int index = head.getAndIncrement() % QUEUE_CAPACITY;
            Payment payment = paymentRing[index];
            paymentRing[index] = null; // Help GC
            availableSlots.release();
            return payment;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public void requeuePayment(Payment request) {
        enqueuePayment(request);
    }

    public void savePayment(Payment request, ProcessorService service) {
        String correlationId = request.correlationId();
        boolean isDefault = ProcessorService.DEFAULT.equals(service);

        PaymentData data = new PaymentData(
                correlationId,
                request.amount(),
                isDefault,
                System.currentTimeMillis()
        );

        paymentsMap.put(correlationId, data);

        ProcessorMetrics metrics = isDefault ? defaultMetrics : fallbackMetrics;
        metrics.add(request.amount());
    }

    public boolean acquireHealthCheckLock() {
        return healthCheckLock.compareAndSet(false, true);
    }

    public void releaseHealthCheckLock() {
        healthCheckLock.set(false);
    }

    public void storeHealthyProcessor(ProcessorService service) {
        healthyProcessor.set(service);
    }

    public ProcessorService getHealthyProcessor() {
        return healthyProcessor.get();
    }

    public PaymentSummary getPaymentsSummary(Instant from, Instant to) {
        if (from == null && to == null) {
            return new PaymentSummary(
                    new PaymentSummary.ProcessorSummary(
                            defaultMetrics.getCount(),
                            defaultMetrics.getTotal()
                    ),
                    new PaymentSummary.ProcessorSummary(
                            fallbackMetrics.getCount(),
                            fallbackMetrics.getTotal()
                    )
            );
        }

        long fromMillis = from != null ? from.toEpochMilli() : Long.MIN_VALUE;
        long toMillis = to != null ? to.toEpochMilli() : Long.MAX_VALUE;

        long defaultCents = 0;
        long fallbackCents = 0;
        long defaultCount = 0;
        long fallbackCount = 0;

        for (PaymentData payment : paymentsMap.values()) {
            if (payment.timestampMillis >= fromMillis && payment.timestampMillis <= toMillis) {
                if (payment.isDefault) {
                    defaultCount++;
                    defaultCents += payment.amountCents;
                } else {
                    fallbackCount++;
                    fallbackCents += payment.amountCents;
                }
            }
        }

        return new PaymentSummary(
                new PaymentSummary.ProcessorSummary(
                        defaultCount,
                        BigDecimal.valueOf(defaultCents).movePointLeft(2)
                ),
                new PaymentSummary.ProcessorSummary(
                        fallbackCount,
                        BigDecimal.valueOf(fallbackCents).movePointLeft(2)
                )
        );
    }

    public void purgeAllData() {
        // Limpar ring buffer
        for (int i = 0; i < QUEUE_CAPACITY; i++) {
            paymentRing[i] = null;
        }
        head.set(0);
        tail.set(0);

        // Resetar semáforos
        availableItems.drainPermits();
        availableSlots.drainPermits();
        availableSlots.release(QUEUE_CAPACITY);

        // Limpar mapas e métricas
        paymentsMap.clear();
        defaultMetrics.reset();
        fallbackMetrics.reset();
    }
}