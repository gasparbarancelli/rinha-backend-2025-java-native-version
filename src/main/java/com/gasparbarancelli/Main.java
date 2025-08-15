package com.gasparbarancelli;

import com.gasparbarancelli.interactor.PaymentService;
import com.gasparbarancelli.transport.PaymentHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.Executors;

public class Main {
    private static final String HTTP_PORT_ENV = "HTTP_PORT";
    private static final int BACKLOG = 4096;

    static {
        System.setProperty("sun.net.httpserver.maxReqTime", "100");
        System.setProperty("sun.net.httpserver.maxRspTime", "100");
        System.setProperty("sun.net.httpserver.nodelay", "true");
        System.setProperty("sun.net.httpserver.maxConnections", "2000");
        System.setProperty("java.net.preferIPv4Stack", "true");
        System.setProperty("java.awt.headless", "true");
        System.setProperty("jdk.httpclient.connectionPoolSize", "100");
        System.setProperty("jdk.httpclient.keepalive.timeout", "10");
        System.setProperty("jdk.virtualThreadScheduler.parallelism", "64");
        System.setProperty("jdk.virtualThreadScheduler.maxPoolSize", "256");
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

        var executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        server.setExecutor(executor);

        System.out.println("Rinha Backend 2025");
        System.out.println("Porta: " + inetSocketAddress.get().getPort());

        server.start();

        long endTime = System.nanoTime();
        long startupTimeNanos = endTime - startTime;
        double startupTimeMillis = startupTimeNanos / 1_000_000.0;
        System.out.println("Servidor HTTP iniciado com sucesso!");
        System.out.printf("Aplicação iniciada em %.3f ms (%.0f nanosegundos)%n",
                startupTimeMillis, (double) startupTimeNanos);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                paymentService.closeRepository();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }));
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