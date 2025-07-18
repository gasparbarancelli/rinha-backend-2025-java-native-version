package com.gasparbarancelli;

import com.gasparbarancelli.interactor.PaymentService;
import com.gasparbarancelli.transport.PaymentHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class Main {

    private static final String HTTP_PORT_ENV = "HTTP_PORT";
    private static final int BACKLOG = 1024;
    private static final int CORE_POOL_SIZE = 15;
    private static final int MAX_POOL_SIZE = 150;
    private static final long KEEP_ALIVE_TIME = 10L;
    private static final int QUEUE_CAPACITY = 1000;

    static {
        System.setProperty("sun.net.httpserver.maxReqTime", "1000");
        System.setProperty("sun.net.httpserver.maxRspTime", "1000");
        System.setProperty("sun.net.httpserver.nodelay", "true");
        System.setProperty("sun.net.httpserver.maxConnections", "1000");

        System.setProperty("java.net.preferIPv4Stack", "true");
        System.setProperty("java.awt.headless", "true");
    }

    public static void main(String[] args) throws IOException {
        long startTime = System.nanoTime();

        var inetSocketAddress = getSocketAddress();
        if (inetSocketAddress.isEmpty()) {
            System.out.println("Defina a variavel de ambiente HTTP_PORT para iniciar o servidor web");
            return;
        }

        var server = HttpServer.create(inetSocketAddress.get(), BACKLOG);

        var paymentService = new PaymentService();
        var paymentHandler = new PaymentHandler(paymentService);

        server.createContext("/payments", paymentHandler::handlePayments);
        server.createContext("/payments-summary", paymentHandler::handlePaymentsSummary);
        server.createContext("/purge-payments", paymentHandler::handlePurgePayments);

        var executor = new ThreadPoolExecutor(
                CORE_POOL_SIZE,
                MAX_POOL_SIZE,
                KEEP_ALIVE_TIME,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                Thread.ofVirtual().factory(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        executor.prestartAllCoreThreads();
        executor.allowCoreThreadTimeOut(true);

        server.setExecutor(executor);

        System.out.println("Rinha Backend 2025 - Otimizado para K6 (2 instâncias)");
        System.out.println("Porta: " + inetSocketAddress.get().getPort());

        server.start();

        long endTime = System.nanoTime();
        long startupTimeNanos = endTime - startTime;
        double startupTimeMillis = startupTimeNanos / 1_000_000.0;

        System.out.printf("Aplicação iniciada em %.3f ms (%.0f nanosegundos)%n",
                startupTimeMillis, (double) startupTimeNanos);
    }

    private static Optional<InetSocketAddress> getSocketAddress() {
        String port = System.getenv(HTTP_PORT_ENV);
        if (port == null || port.isEmpty()) {
            return Optional.empty();
        }

        try {
            return Optional.of(new InetSocketAddress(Integer.parseInt(port)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}