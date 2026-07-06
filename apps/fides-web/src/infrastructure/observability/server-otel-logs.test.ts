import { ExportResultCode } from "@opentelemetry/core";
import type { ReadableLogRecord, LogRecordExporter } from "@opentelemetry/sdk-logs";
import { afterEach, describe, expect, it, vi } from "vitest";

import {
  createServerOtelLogExporter,
  resetServerOtelLogExporterForTest,
  type ServerOtelLogRecord,
} from "./server-otel-logs";

class CapturingLogRecordExporter implements LogRecordExporter {
  records: ReadableLogRecord[] = [];

  export(records: ReadableLogRecord[], resultCallback: (result: { code: ExportResultCode }) => void) {
    this.records.push(...records);
    resultCallback({ code: ExportResultCode.SUCCESS });
  }

  async shutdown() {
    this.records = [];
  }

  async forceFlush() {}
}

describe("server OTEL logs", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    resetServerOtelLogExporterForTest();
  });

  it("stays disabled unless OTEL_LOGS_EXPORTER is otlp", () => {
    vi.stubEnv("FIDES_BFF_BASE_URL", "http://127.0.0.1:8000/api/v1");

    expect(createServerOtelLogExporter()).toBeUndefined();
  });

  it("exports safe server log records with trace and request correlation attributes", async () => {
    vi.stubEnv("FIDES_RUNTIME_ENV", "dev-1");
    vi.stubEnv("FIDES_BFF_BASE_URL", "http://127.0.0.1:8000/api/v1");
    vi.stubEnv("OTEL_LOGS_EXPORTER", "otlp");
    vi.stubEnv("OTEL_EXPORTER_OTLP_LOGS_ENDPOINT", "https://otel.example/v1/logs");
    vi.stubEnv("OTEL_SERVICE_NAME", "fides-web");
    const exporter = new CapturingLogRecordExporter();
    const otelLogs = createServerOtelLogExporter({ exporter });
    const record: ServerOtelLogRecord = {
      timestamp: "2026-07-06T00:00:00.000Z",
      level: "INFO",
      service: "fides-web",
      operation: "runtime_config.request",
      deployment_environment: "dev-1",
      trace_id: "4bf92f3577b34da6a3ce929d0e0e4736",
      span_id: "00f067aa0ba902b7",
      request_id: "req_123",
      route: "/api/runtime-config",
      status: 200,
    };

    otelLogs?.emit(record);
    await otelLogs?.forceFlush();

    expect(exporter.records).toHaveLength(1);
    expect(exporter.records[0]).toMatchObject({
      body: "runtime_config.request",
      severityText: "INFO",
      severityNumber: 9,
      spanContext: {
        traceId: "4bf92f3577b34da6a3ce929d0e0e4736",
        spanId: "00f067aa0ba902b7",
      },
      attributes: {
        service: "fides-web",
        operation: "runtime_config.request",
        deployment_environment: "dev-1",
        trace_id: "4bf92f3577b34da6a3ce929d0e0e4736",
        span_id: "00f067aa0ba902b7",
        request_id: "req_123",
        "service.name": "fides-web",
        "deployment.environment": "dev-1",
      },
    });
  });

  it("writes a single safe diagnostic when exporter initialization fails", async () => {
    vi.stubEnv("FIDES_RUNTIME_ENV", "dev-1");
    vi.stubEnv("FIDES_BFF_BASE_URL", "http://127.0.0.1:8000/api/v1");
    vi.stubEnv("OTEL_LOGS_EXPORTER", "otlp");
    vi.stubEnv("OTEL_EXPORTER_OTLP_LOGS_ENDPOINT", "");
    vi.stubEnv("OTEL_EXPORTER_OTLP_LOGS_HEADERS", "x-sentry-auth=secret-token");
    const sink = vi.fn();
    const record: ServerOtelLogRecord = {
      timestamp: "2026-07-06T00:00:00.000Z",
      level: "INFO",
      service: "fides-web",
      operation: "runtime_config.request",
      deployment_environment: "dev-1",
      trace_id: "4bf92f3577b34da6a3ce929d0e0e4736",
      request_id: "req_123",
    };

    const { emitServerOtelLog } = await import("./server-otel-logs");
    emitServerOtelLog(record, sink);
    emitServerOtelLog(record, sink);

    expect(sink).toHaveBeenCalledTimes(1);
    const diagnostic = JSON.parse(sink.mock.calls[0]?.[0] as string) as Record<string, unknown>;
    expect(diagnostic).toMatchObject({
      level: "WARN",
      service: "fides-web",
      operation: "server_otel_logs.exporter_error",
      deployment_environment: "dev-1",
      error_code: "FIDES-OBSERVABILITY-0001",
      error_type: "ZodError",
      trace_id: "4bf92f3577b34da6a3ce929d0e0e4736",
      request_id: "req_123",
    });
    expect(JSON.stringify(diagnostic)).not.toContain("secret-token");
  });
});
