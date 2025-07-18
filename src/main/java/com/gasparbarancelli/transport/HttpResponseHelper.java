package com.gasparbarancelli.transport;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class HttpResponseHelper {

    private static final byte[] METHOD_NOT_ALLOWED = "Method Not Allowed".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INVALID_REQUEST = """
            {"error":"Invalid request"}
            """.getBytes(StandardCharsets.UTF_8);
    private static final byte[] INTERNAL_ERROR = """
            {"error":"Internal server error"}""".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PURGE_SUCCESS = """
            {"status":"success","message":"Payment data purged successfully"}""".getBytes(StandardCharsets.UTF_8);
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";

    public static void sendMethodNotAllowed(HttpExchange exchange) throws IOException {
        sendResponse(exchange, 405, METHOD_NOT_ALLOWED);
    }

    public static void sendInvalidRequest(HttpExchange exchange) throws IOException {
        sendResponse(exchange, 400, INVALID_REQUEST);
    }

    public static void sendInternalError(HttpExchange exchange) throws IOException {
        sendResponse(exchange, 500, INTERNAL_ERROR);
    }

    public static void sendPurgeSuccess(HttpExchange exchange) throws IOException {
        sendResponse(exchange, 200, PURGE_SUCCESS);
    }

    public static void sendErrorResponse(HttpExchange exchange, int statusCode, byte[] messageBytes) throws IOException {
        sendResponse(exchange, statusCode, messageBytes);
    }

    public static void sendJsonResponse(HttpExchange exchange, int statusCode, byte[] jsonBytes) throws IOException {
        sendResponse(exchange, statusCode, jsonBytes);
    }

    public static void sendResponse(HttpExchange exchange, int statusCode, byte[] responseBytes) throws IOException {
        try (exchange) {
            exchange.getResponseHeaders().set(CONTENT_TYPE, APPLICATION_JSON);
            exchange.sendResponseHeaders(statusCode, responseBytes.length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
                os.flush();
            }
        }
    }
}