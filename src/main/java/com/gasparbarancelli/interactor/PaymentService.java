package com.gasparbarancelli.interactor;

import com.gasparbarancelli.datasource.PaymentRepositoryInMemory;
import com.gasparbarancelli.entity.Payment;
import com.gasparbarancelli.entity.PaymentSummary;
import com.gasparbarancelli.entity.ProcessorService;
import com.gasparbarancelli.repository.PaymentRepository;
import com.gasparbarancelli.transport.JsonUtils;
import com.gasparbarancelli.transport.model.ServiceHealthRequest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public class PaymentService {
    private final PaymentRepository repository;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService workers;
    private final AtomicReference<ProcessorState> healthyProcessor;
    private final URI defaultPaymentUri;
    private final URI fallbackPaymentUri;
    private final URI defaultHealthUri;
    private final URI fallbackHealthUri;
    private volatile long lastHealthCheck = 0;
    private static final long HEALTH_CACHE_MS = 5000;

    private static final int WORKER_COUNT = 20;
    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(100);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(500);
    private static final Duration HEALTH_CHECK_TIMEOUT = Duration.ofMillis(200);
    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";

    private record ProcessorState(ProcessorService service, URI uri) {
    }

    public PaymentService() {
        this.repository = new PaymentRepositoryInMemory();

        String defaultBase = System.getenv().getOrDefault(
                "PAYMENT_PROCESSOR_URL_DEFAULT",
                "http://localhost:8001"
        );
        String fallbackBase = System.getenv().getOrDefault(
                "PAYMENT_PROCESSOR_URL_FALLBACK",
                "http://localhost:8002"
        );

        this.defaultPaymentUri = URI.create(defaultBase + "/payments");
        this.fallbackPaymentUri = URI.create(fallbackBase + "/payments");
        this.defaultHealthUri = URI.create(defaultBase + "/payments/service-health");
        this.fallbackHealthUri = URI.create(fallbackBase + "/payments/service-health");

        this.healthyProcessor = new AtomicReference<>(new ProcessorState(ProcessorService.DEFAULT, defaultPaymentUri));

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1)
                .executor(ForkJoinPool.commonPool())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = Thread.ofVirtual().unstarted(r);
            t.setName("health-checker");
            return t;
        });

        this.workers = Executors.newVirtualThreadPerTaskExecutor();

        startWorkers();
        startHealthChecks();
    }

    private void startWorkers() {
        for (int i = 0; i < WORKER_COUNT; i++) {
            final int workerId = i;
            workers.submit(() -> processPaymentsLoop(workerId));
        }
    }

    private void processPaymentsLoop(int workerId) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Payment request = repository.dequeuePayment(workerId);
                if (request != null) {
                    processPaymentToProcessor(request);
                }
            } catch (Exception ignore) {
            }
        }
    }

    private void processPaymentToProcessor(Payment request) {
        try {
            ProcessorState currentProcessor = getCachedHealthyProcessor();
            String requestBody = JsonUtils.toJson(request);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(currentProcessor.uri)
                    .header(CONTENT_TYPE_HEADER, APPLICATION_JSON)
                    .timeout(REQUEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<Void> response = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.discarding());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                repository.savePayment(request, currentProcessor.service);
            }
        } catch (Exception ignore) {
        }
    }

    private ProcessorState getCachedHealthyProcessor() {
        long now = System.currentTimeMillis();
        if (now - lastHealthCheck < HEALTH_CACHE_MS) {
            return healthyProcessor.get();
        }
        scheduler.execute(this::performHealthCheck);
        return healthyProcessor.get();
    }

    private void startHealthChecks() {
        scheduler.scheduleWithFixedDelay(this::performHealthCheck, 0, 6, TimeUnit.SECONDS);
    }

    private void performHealthCheck() {
        try {
            if (repository.acquireHealthCheckLock()) {
                try {
                    updateHealthyProcessor();
                    lastHealthCheck = System.currentTimeMillis();
                } finally {
                    repository.releaseHealthCheckLock();
                }
            } else {
                readHealthStatusFromRepository();
            }
        } catch (Exception ignore) {
        }
    }

    private void updateHealthyProcessor() {
        CompletableFuture<Boolean> defaultCheck = CompletableFuture.supplyAsync(() ->
                checkHealth(defaultHealthUri), workers);
        CompletableFuture<Boolean> fallbackCheck = CompletableFuture.supplyAsync(() ->
                checkHealth(fallbackHealthUri), workers);

        try {
            Boolean defaultHealthy = defaultCheck.get(300, TimeUnit.MILLISECONDS);
            Boolean fallbackHealthy = fallbackCheck.get(300, TimeUnit.MILLISECONDS);

            ProcessorService selectedService;
            URI selectedUri;

            if (defaultHealthy) {
                selectedService = ProcessorService.DEFAULT;
                selectedUri = defaultPaymentUri;
            } else if (fallbackHealthy) {
                selectedService = ProcessorService.FALLBACK;
                selectedUri = fallbackPaymentUri;
            } else {
                return;
            }

            healthyProcessor.set(new ProcessorState(selectedService, selectedUri));
            repository.storeHealthyProcessor(selectedService);
        } catch (Exception ignore) {

        }
    }

    private boolean checkHealth(URI healthUri) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(healthUri)
                    .timeout(HEALTH_CHECK_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                ServiceHealthRequest health = JsonUtils.parseServiceHealth(response.body());
                return !health.failing();
            }

            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private void readHealthStatusFromRepository() {
        ProcessorService service = repository.getHealthyProcessor();

        if (ProcessorService.DEFAULT.equals(service)) {
            healthyProcessor.set(new ProcessorState(ProcessorService.DEFAULT, defaultPaymentUri));
        } else if (ProcessorService.FALLBACK.equals(service)) {
            healthyProcessor.set(new ProcessorState(ProcessorService.FALLBACK, fallbackPaymentUri));
        }
    }

    public void processPayment(Payment request) {
        CompletableFuture.supplyAsync(() -> {
                repository.enqueuePayment(request);
                return true;
        }, workers);
    }

    public PaymentSummary getPaymentsSummary() {
        return repository.getPaymentsSummary(null, null);
    }

    public PaymentSummary getPaymentsSummary(Instant from, Instant to) {
        return repository.getPaymentsSummary(from, to);
    }

    public void purgeAllData() {
        repository.purgeAllData();
    }

    public void closeRepository() throws IOException {
        repository.close();
    }

}