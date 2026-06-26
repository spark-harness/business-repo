package com.spark.applicant.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        properties = {
            "spark.grpc.server.enabled=false",
            "spark.applicant.auth.runtime-store=redis-jdbc",
            "spark.applicant.auth.token-mode=hmac",
            "spark.applicant.auth.token-secret=test-secret",
            "spark.applicant.auth.jdbc-url=jdbc:h2:mem:redis-trace-export;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spark.applicant.auth.jdbc-username=sa",
            "spark.applicant.auth.jdbc-password=",
            "spark.applicant.auth.migrations-enabled=true",
            "spark.applicant.auth.consul.enabled=false",
            "spring.data.redis.host=localhost",
            "spring.data.redis.port=6379",
            "spring.data.redis.password=forest_dev_password",
            "otel.traces.exporter=otlp",
            "otel.exporter.otlp.traces.protocol=http/protobuf",
            "otel.traces.sampler=always_on",
            "otel.bsp.schedule.delay=50",
            "otel.bsp.max.export.batch.size=1",
            "otel.bsp.max.queue.size=16"
        })
class RedisTraceExportTest {
    private static final OtlpTraceReceiver traceReceiver = OtlpTraceReceiver.start();

    @Autowired
    private StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void otlpProperties(DynamicPropertyRegistry registry) {
        registry.add("otel.exporter.otlp.traces.endpoint", traceReceiver::endpoint);
    }

    @AfterAll
    static void stopTraceReceiver() {
        traceReceiver.stop();
    }

    @Test
    void redisCommand_whenOtlpExporterIsConfigured_shouldExportRedisTrace() throws Exception {
        redisTemplate.opsForValue().set("applicant-api:test:redis-trace", "ok");
        redisTemplate.opsForValue().get("applicant-api:test:redis-trace");

        assertThat(traceReceiver.awaitTraceExportContaining("redis", "SET", "GET")).isTrue();
        assertThat(traceReceiver.traceBodies()).anySatisfy(body -> {
            String tracePayload = new String(body, StandardCharsets.ISO_8859_1);
            assertThat(tracePayload).contains("redis");
            assertThat(tracePayload).containsAnyOf("SET", "GET");
        });
    }

    private static final class OtlpTraceReceiver {
        private final HttpServer server;
        private final List<byte[]> traceBodies = new ArrayList<>();

        private OtlpTraceReceiver(HttpServer server) {
            this.server = server;
        }

        static OtlpTraceReceiver start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
                OtlpTraceReceiver receiver = new OtlpTraceReceiver(server);
                server.createContext("/v1/traces", receiver::handleTraceExport);
                server.start();
                return receiver;
            } catch (IOException exception) {
                throw new IllegalStateException("failed to start local OTLP trace receiver", exception);
            }
        }

        String endpoint() {
            return "http://localhost:" + server.getAddress().getPort() + "/v1/traces";
        }

        boolean awaitTraceExportContaining(String... tokens) throws InterruptedException {
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            synchronized (this) {
                while (System.nanoTime() < deadlineNanos) {
                    if (traceBodies.stream().anyMatch(body -> containsAnyToken(body, tokens))) {
                        return true;
                    }
                    TimeUnit.NANOSECONDS.timedWait(this, Math.max(1, deadlineNanos - System.nanoTime()));
                }
                return traceBodies.stream().anyMatch(body -> containsAnyToken(body, tokens));
            }
        }

        private boolean containsAnyToken(byte[] body, String... tokens) {
            String tracePayload = new String(body, StandardCharsets.ISO_8859_1);
            for (String token : tokens) {
                if (tracePayload.contains(token)) {
                    return true;
                }
            }
            return false;
        }

        synchronized List<byte[]> traceBodies() {
            return List.copyOf(traceBodies);
        }

        void stop() {
            server.stop(0);
        }

        private void handleTraceExport(HttpExchange exchange) throws IOException {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            synchronized (this) {
                traceBodies.add(requestBody);
                notifyAll();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        }
    }
}
