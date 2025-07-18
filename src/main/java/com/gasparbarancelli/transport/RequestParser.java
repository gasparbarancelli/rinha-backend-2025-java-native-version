package com.gasparbarancelli.transport;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class RequestParser {
    private static final DateTimeFormatter FLEXIBLE_FORMATTER = DateTimeFormatter
            .ofPattern("[yyyy-MM-dd'T'HH:mm:ss][yyyy-MM-dd]")
            .withZone(ZoneId.of("UTC"));

    private static final ZoneId UTC_ZONE = ZoneId.of("UTC");

    public static Map<String, String> parseQueryParams(String query) {
        if (query == null || query.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, String> params = new HashMap<>();
        String[] pairs = query.split("&");

        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx > 0 && idx < pair.length() - 1) {
                params.put(pair.substring(0, idx), pair.substring(idx + 1));
            }
        }

        return params;
    }

    public static Instant parseFlexibleTime(String timeStr) {
        if (timeStr == null || timeStr.isEmpty()) {
            return null;
        }

        try {
            return Instant.parse(timeStr);
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(timeStr, FLEXIBLE_FORMATTER)
                        .atZone(UTC_ZONE)
                        .toInstant();
            } catch (DateTimeParseException ex) {
                throw new IllegalArgumentException("Invalid ISO UTC date format: " + timeStr);
            }
        }
    }

    public static void validateTimeRange(Instant from, Instant to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("'from' must be before or equal to 'to'");
        }
    }

}