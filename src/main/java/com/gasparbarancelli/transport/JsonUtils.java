package com.gasparbarancelli.transport;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.gasparbarancelli.entity.Payment;
import com.gasparbarancelli.entity.PaymentSummary;
import com.gasparbarancelli.transport.model.PaymentRequestResponse;
import com.gasparbarancelli.transport.model.PaymentSummaryResponse;
import com.gasparbarancelli.transport.model.ServiceHealthRequest;
import com.gasparbarancelli.transport.model.ServiceHealthResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

public class JsonUtils {

    private static final ObjectMapper OBJECT_MAPPER;
    private static final JsonFactory JSON_FACTORY;
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT;
    private static final ObjectReader PAYMENT_REQUEST_READER;
    private static final ObjectReader SERVICE_HEALTH_READER;
    private static final ObjectWriter DEFAULT_WRITER;
    private static final ThreadLocal<byte[]> BUFFER_CACHE =
            ThreadLocal.withInitial(() -> new byte[2048]);
    private static final String PAYMENT_RESPONSE_TEMPLATE =
            "{\"status\":\"success\",\"message\":\"Payment request accepted\",\"correlationId\":\"%s\"}";
    private static final byte[] PAYMENT_RESPONSE_PREFIX =
            "{\"status\":\"success\",\"message\":\"Payment request accepted\",\"correlationId\":\"".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PAYMENT_RESPONSE_SUFFIX =
            "\"}".getBytes(StandardCharsets.UTF_8);

    static {
        JSON_FACTORY = new JsonFactory();
        OBJECT_MAPPER = new ObjectMapper(JSON_FACTORY);

        OBJECT_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        OBJECT_MAPPER.configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, false);
        OBJECT_MAPPER.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        OBJECT_MAPPER.setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE);
        OBJECT_MAPPER.setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE);
        OBJECT_MAPPER.findAndRegisterModules();

        PAYMENT_REQUEST_READER = OBJECT_MAPPER.readerFor(PaymentRequestResponse.class);
        SERVICE_HEALTH_READER = OBJECT_MAPPER.readerFor(ServiceHealthResponse.class);
        DEFAULT_WRITER = OBJECT_MAPPER.writer();
    }

    public static Payment parsePaymentRequestDirect(byte[] jsonBytes) {
        try {
            PaymentRequestResponse response = PAYMENT_REQUEST_READER.readValue(jsonBytes);
            return new Payment(
                    response.correlationId(),
                    response.amount()
            );
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getMessage(), e);
        }
    }

    public static String toJson(Payment request) {
        try {
            PaymentRequestResponse response = new PaymentRequestResponse(
                    request.correlationId(),
                    request.amount(),
                    Instant.now().atOffset(ZoneOffset.UTC).format(ISO_FORMATTER)
            );

            return DEFAULT_WRITER.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error creating JSON", e);
        }
    }

    public static byte[] toPaymentSummaryJsonBytes(PaymentSummary summary) {
        try {
            PaymentSummaryResponse response = new PaymentSummaryResponse(
                    new PaymentSummaryResponse.ProcessorSummaryResponse(
                            summary.defaultProcessor().totalRequests(),
                            summary.defaultProcessor().totalAmount()
                    ),
                    new PaymentSummaryResponse.ProcessorSummaryResponse(
                            summary.fallback().totalRequests(),
                            summary.fallback().totalAmount()
                    )
            );

            return DEFAULT_WRITER.writeValueAsBytes(response);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error creating payment summary JSON", e);
        }
    }

    public static ServiceHealthRequest parseServiceHealth(String json) {
        try {
            ServiceHealthResponse response = SERVICE_HEALTH_READER.readValue(json);
            return new ServiceHealthRequest(response.failing(), response.minResponseTime());
        } catch (IOException e) {
            throw new RuntimeException("Error parsing service health", e);
        }
    }

    public static byte[] createPaymentAcceptedResponse(String correlationId) {
        byte[] correlationBytes = correlationId.getBytes(StandardCharsets.UTF_8);
        int totalSize = PAYMENT_RESPONSE_PREFIX.length + correlationBytes.length + PAYMENT_RESPONSE_SUFFIX.length;

        byte[] buffer = BUFFER_CACHE.get();
        if (totalSize <= buffer.length) {
            int pos = 0;
            System.arraycopy(PAYMENT_RESPONSE_PREFIX, 0, buffer, pos, PAYMENT_RESPONSE_PREFIX.length);
            pos += PAYMENT_RESPONSE_PREFIX.length;
            System.arraycopy(correlationBytes, 0, buffer, pos, correlationBytes.length);
            pos += correlationBytes.length;
            System.arraycopy(PAYMENT_RESPONSE_SUFFIX, 0, buffer, pos, PAYMENT_RESPONSE_SUFFIX.length);
            return Arrays.copyOf(buffer, totalSize);
        } else {
            // Fallback para strings grandes
            return String.format(PAYMENT_RESPONSE_TEMPLATE, correlationId).getBytes(StandardCharsets.UTF_8);
        }
    }

}