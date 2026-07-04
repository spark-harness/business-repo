package com.spark.applicant.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        properties = {
            "spark.grpc.server.enabled=false",
            "spark.applicant.auth.runtime-store=redis-jdbc",
            "spark.applicant.auth.token-mode=hmac",
            "spark.applicant.auth.token-secret=test-secret",
            "spark.applicant.auth.jdbc-url=jdbc:h2:mem:jdbc-trace-export;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spark.applicant.auth.jdbc-username=sa",
            "spark.applicant.auth.jdbc-password=",
            "spark.applicant.auth.migrations-enabled=true",
            "spark.applicant.auth.consul.enabled=false",
            "spring.data.redis.host=localhost",
            "spring.data.redis.password=forest_dev_password",
            "otel.traces.exporter=otlp",
            "otel.exporter.otlp.traces.protocol=http/protobuf",
            "otel.traces.sampler=always_on",
            "otel.bsp.schedule.delay=50",
            "otel.bsp.max.export.batch.size=1",
            "otel.bsp.max.queue.size=16"
        })
class JdbcTraceExportTest {
    private static final OtlpTraceReceiver traceReceiver = OtlpTraceReceiver.start();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void otlpProperties(DynamicPropertyRegistry registry) {
        registry.add("otel.exporter.otlp.traces.endpoint", traceReceiver::endpoint);
    }

    @AfterAll
    static void stopTraceReceiver() {
        traceReceiver.stop();
    }

    @Test
    void jdbcQuery_whenOtlpExporterIsConfigured_shouldExportDatabaseTrace() throws Exception {
        jdbcTemplate.queryForObject("select count(*) from applicants", Integer.class);

        assertThat(traceReceiver.awaitTraceExport()).isTrue();
        assertThat(traceReceiver.traceBodies())
                .anySatisfy(body -> assertThat(new String(body, StandardCharsets.ISO_8859_1)).contains("SELECT"));
    }

    private static final class OtlpTraceReceiver {
        private final HttpServer server;
        private final CountDownLatch latch = new CountDownLatch(1);
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

        boolean awaitTraceExport() throws InterruptedException {
            return latch.await(10, TimeUnit.SECONDS);
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
            }
            latch.countDown();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        }
    }
}
