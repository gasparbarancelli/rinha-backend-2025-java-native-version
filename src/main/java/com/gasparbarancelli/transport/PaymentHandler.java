package com.gasparbarancelli.transport;

import com.gasparbarancelli.interactor.PaymentService;
import com.gasparbarancelli.entity.PaymentSummary;
import com.gasparbarancelli.entity.Payment;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class PaymentHandler {
    private final PaymentService paymentService;
    private static final String POST = "POST";
    private static final String GET = "GET";
    private static final String STATUS_KEY = "status";
    private static final String MESSAGE_KEY = "message";
    private static final String CORRELATION_ID_KEY = "correlationId";
    private static final String SUCCESS_VALUE = "success";
    private static final String PAYMENT_ACCEPTED_MSG = "Payment request accepted";
    private static final byte[] AMOUNT_ERROR = "Amount must be greater than zero".getBytes();
    private static final byte[] PROCESS_ERROR = "Failed to process payment".getBytes();

    public PaymentHandler(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    public void handlePayments(HttpExchange exchange) throws IOException {
        if (!POST.equals(exchange.getRequestMethod())) {
            HttpResponseHelper.sendMethodNotAllowed(exchange);
            return;
        }

        try {
            byte[] requestBytes = exchange.getRequestBody().readAllBytes();
            Payment payment = JsonUtils.parsePaymentRequestDirect(requestBytes);

            if (payment.amount().compareTo(BigDecimal.ZERO) <= 0) {
                HttpResponseHelper.sendErrorResponse(exchange, 400, AMOUNT_ERROR);
                return;
            }

            CompletableFuture<Boolean> future = paymentService.processPayment(payment);

            future.whenComplete((success, _) -> {
                try {
                    if (success != null && success) {
                        sendPaymentAcceptedResponse(exchange, payment);
                    } else {
                        HttpResponseHelper.sendErrorResponse(exchange, 503, PROCESS_ERROR);
                    }
                } catch (IOException ignore) {
                }
            });
        } catch (IllegalArgumentException e) {
            HttpResponseHelper.sendInvalidRequest(exchange);
        } catch (Exception e) {
            HttpResponseHelper.sendInternalError(exchange);
        }
    }

    public void handlePaymentsSummary(HttpExchange exchange) throws IOException {
        if (!GET.equals(exchange.getRequestMethod())) {
            HttpResponseHelper.sendMethodNotAllowed(exchange);
            return;
        }

        try {
            Map<String, String> queryParams = RequestParser.parseQueryParams(exchange.getRequestURI().getQuery());

            Instant from = RequestParser.parseFlexibleTime(queryParams.get("from"));
            Instant to = RequestParser.parseFlexibleTime(queryParams.get("to"));

            RequestParser.validateTimeRange(from, to);

            PaymentSummary summary = (from != null && to != null)
                    ? paymentService.getPaymentsSummary(from, to)
                    : paymentService.getPaymentsSummary();

            byte[] jsonBytes = JsonUtils.toPaymentSummaryJsonBytes(summary);
            HttpResponseHelper.sendJsonResponse(exchange, 200, jsonBytes);
        } catch (IllegalArgumentException e) {
            HttpResponseHelper.sendErrorResponse(exchange, 400, e.getMessage().getBytes());
        } catch (Exception e) {
            HttpResponseHelper.sendInternalError(exchange);
        }
    }

    public void handlePurgePayments(HttpExchange exchange) throws IOException {
        if (!POST.equals(exchange.getRequestMethod())) {
            HttpResponseHelper.sendMethodNotAllowed(exchange);
            return;
        }

        try {
            paymentService.purgeAllData();
            HttpResponseHelper.sendPurgeSuccess(exchange);
        } catch (Exception e) {
            HttpResponseHelper.sendInternalError(exchange);
        }
    }

    private void sendPaymentAcceptedResponse(HttpExchange exchange, Payment payment) throws IOException {
        String correlationId = payment.correlationId();
        byte[] response = JsonUtils.createSimpleJsonResponse(
                STATUS_KEY, SUCCESS_VALUE,
                MESSAGE_KEY, PAYMENT_ACCEPTED_MSG,
                CORRELATION_ID_KEY, correlationId
        );

        HttpResponseHelper.sendJsonResponse(exchange, 202, response);
    }
}