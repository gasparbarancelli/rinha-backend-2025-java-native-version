package com.gasparbarancelli.transport;

import com.gasparbarancelli.interactor.PaymentService;
import com.gasparbarancelli.entity.PaymentSummary;
import com.gasparbarancelli.entity.Payment;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public class PaymentHandler {
    private final PaymentService paymentService;
    private static final String POST = "POST";
    private static final String GET = "GET";
    private static final byte[] AMOUNT_ERROR = "Amount must be greater than zero".getBytes();

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

            paymentService.processPayment(payment);
            sendPaymentAcceptedResponse(exchange);

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

    private void sendPaymentAcceptedResponse(HttpExchange exchange) throws IOException {
        HttpResponseHelper.sendResponse(exchange, 200);
    }
}