package com.gasparbarancelli.datasource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gasparbarancelli.entity.Payment;
import com.gasparbarancelli.entity.PaymentSummary;
import com.gasparbarancelli.entity.ProcessorService;
import com.gasparbarancelli.repository.PaymentRepository;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.params.SetParams;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class PaymentRepositoryInMemory implements PaymentRepository {

    private static final String PAYMENT_QUEUE_KEY = "payment_queue";
    private static final String SUMMARY_KEY = "payment_summary";
    private static final String PAYMENTS_BY_TIME_KEY = "payments_by_time";
    private static final String HEALTH_LOCK_KEY = "health_check_lock";
    private static final String HEALTHY_PROCESSOR_KEY = "healthy_processor";
    private final JedisPool jedisPool;
    private final ObjectMapper objectMapper;
    private static final String SUMMARY_LUA_SCRIPT =
            "local payments = redis.call('ZRANGEBYSCORE', KEYS[1], ARGV[1], ARGV[2])\n" +
                    "local default_cents = 0\n" +
                    "local fallback_cents = 0\n" +
                    "local default_count = 0\n" +
                    "local fallback_count = 0\n" +
                    "for i, payment_details in ipairs(payments) do\n" +
                    "    local _, amount_str, is_default_str = string.match(payment_details, '([^:]+):([^:]+):([^:]+)')\n" +
                    "    local amount = tonumber(amount_str)\n" +
                    "    if is_default_str == 'true' then\n" +
                    "        default_count = default_count + 1\n" +
                    "        default_cents = default_cents + amount\n" +
                    "    else\n" +
                    "        fallback_count = fallback_count + 1\n" +
                    "        fallback_cents = fallback_cents + amount\n" +
                    "    end\n" +
                    "end\n" +
                    "return {tostring(default_count), tostring(default_cents), tostring(fallback_count), tostring(fallback_cents)}";

    private final String LUA_SCRIPT_SHA;


    public PaymentRepositoryInMemory() {
        final JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(500);
        poolConfig.setMaxIdle(500);
        poolConfig.setMinIdle(100);
        poolConfig.setBlockWhenExhausted(true);

        String valkeyHost = System.getenv().getOrDefault("VALKEY_HOST", "localhost");
        int valkeyPort = Integer.parseInt(System.getenv().getOrDefault("VALKEY_PORT", "6379"));
        this.jedisPool = new JedisPool(poolConfig, valkeyHost, valkeyPort, 2000);

        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());

        try (Jedis jedis = jedisPool.getResource()) {
            this.LUA_SCRIPT_SHA = jedis.scriptLoad(SUMMARY_LUA_SCRIPT);
        }
    }

    @Override
    public void enqueuePayment(Payment request) {
        try (Jedis jedis = jedisPool.getResource()) {
            String serializedPayment = objectMapper.writeValueAsString(request);
            jedis.lpush(PAYMENT_QUEUE_KEY, serializedPayment);
        } catch (JsonProcessingException ignore) {
        }
    }

    @Override
    public Payment dequeuePayment(int workerId) {
        try (Jedis jedis = jedisPool.getResource()) {
            List<String> result = jedis.brpop(1, PAYMENT_QUEUE_KEY);
            if (result != null && !result.isEmpty()) {
                String serializedPayment = result.get(1);
                return objectMapper.readValue(serializedPayment, Payment.class);
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void savePayment(Payment request, ProcessorService service) {
        boolean isDefault = ProcessorService.DEFAULT.equals(service);
        long timestamp = System.currentTimeMillis();
        long amountCents = request.amount().movePointRight(2).longValue();
        String paymentDetails = String.join(":", request.correlationId(), String.valueOf(amountCents), String.valueOf(isDefault));

        try (Jedis jedis = jedisPool.getResource()) {
            Pipeline p = jedis.pipelined();

            p.zadd(PAYMENTS_BY_TIME_KEY, timestamp, paymentDetails);

            if (isDefault) {
                p.hincrBy(SUMMARY_KEY, "default_count", 1L);
                p.hincrBy(SUMMARY_KEY, "default_total_cents", amountCents);
            } else {
                p.hincrBy(SUMMARY_KEY, "fallback_count", 1L);
                p.hincrBy(SUMMARY_KEY, "fallback_total_cents", amountCents);
            }
            p.sync();
        }
    }

    @Override
    public PaymentSummary getPaymentsSummary(Instant from, Instant to) {
        if (from == null && to == null) {
            try (Jedis jedis = jedisPool.getResource()) {
                Map<String, String> summaryData = jedis.hgetAll(SUMMARY_KEY);
                long defaultCount = Long.parseLong(summaryData.getOrDefault("default_count", "0"));
                long defaultTotalCents = Long.parseLong(summaryData.getOrDefault("default_total_cents", "0"));
                long fallbackCount = Long.parseLong(summaryData.getOrDefault("fallback_count", "0"));
                long fallbackTotalCents = Long.parseLong(summaryData.getOrDefault("fallback_total_cents", "0"));

                return new PaymentSummary(
                        new PaymentSummary.ProcessorSummary(defaultCount, BigDecimal.valueOf(defaultTotalCents, 2)),
                        new PaymentSummary.ProcessorSummary(fallbackCount, BigDecimal.valueOf(fallbackTotalCents, 2))
                );
            }
        }

        try (Jedis jedis = jedisPool.getResource()) {
            long fromMillis = from.toEpochMilli();
            long toMillis = to.toEpochMilli();

            List<String> keys = Collections.singletonList(PAYMENTS_BY_TIME_KEY);
            List<String> args = List.of(String.valueOf(fromMillis), String.valueOf(toMillis));

            @SuppressWarnings("unchecked")
            List<String> result = (List<String>) jedis.evalsha(LUA_SCRIPT_SHA, keys, args);

            long defaultCount = Long.parseLong(result.get(0));
            long defaultCents = Long.parseLong(result.get(1));
            long fallbackCount = Long.parseLong(result.get(2));
            long fallbackCents = Long.parseLong(result.get(3));

            return new PaymentSummary(
                    new PaymentSummary.ProcessorSummary(defaultCount, BigDecimal.valueOf(defaultCents, 2)),
                    new PaymentSummary.ProcessorSummary(fallbackCount, BigDecimal.valueOf(fallbackCents, 2))
            );
        }
    }

    @Override
    public boolean acquireHealthCheckLock() {
        try (Jedis jedis = jedisPool.getResource()) {
            String result = jedis.set(HEALTH_LOCK_KEY, "locked", SetParams.setParams().nx().ex(10));
            return "OK".equals(result);
        }
    }

    @Override public void releaseHealthCheckLock() {
        try (Jedis jedis = jedisPool.getResource()) { jedis.del(HEALTH_LOCK_KEY); }
    }
    @Override public ProcessorService getHealthyProcessor() {
        try (Jedis jedis = jedisPool.getResource()) {
            String service = jedis.get(HEALTHY_PROCESSOR_KEY);
            return "FALLBACK".equals(service) ? ProcessorService.FALLBACK : ProcessorService.DEFAULT;
        }
    }
    @Override public void storeHealthyProcessor(ProcessorService service) {
        try (Jedis jedis = jedisPool.getResource()) { jedis.set(HEALTHY_PROCESSOR_KEY, service.name()); }
    }
    @Override public void purgeAllData() {
        try (Jedis jedis = jedisPool.getResource()) { jedis.flushDB(); }
    }
    @Override public void close() {
        if (jedisPool != null && !jedisPool.isClosed()) { jedisPool.close(); }
    }
}