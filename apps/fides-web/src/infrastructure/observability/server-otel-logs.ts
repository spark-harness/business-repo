import { context, isSpanContextValid, ROOT_CONTEXT, trace, TraceFlags } from "@opentelemetry/api";
import { OTLPLogExporter } from "@opentelemetry/exporter-logs-otlp-http";
import { resourceFromAttributes } from "@opentelemetry/resources";
import {
  LoggerProvider,
  type LogRecordExporter,
  SimpleLogRecordProcessor,
} from "@opentelemetry/sdk-logs";

import { getFidesEnv, getRuntimeEnvironmentFromEnv } from "@/config/env";

export type ServerOtelLogRecord = {
  timestamp: string;
  level: "INFO" | "WARN" | "ERROR";
  service: string;
  operation: string;
  deployment_environment: string;
  trace_id?: string;
  span_id?: string;
  request_id?: string;
  [key: string]: string | number | boolean | undefined;
};

export type ServerOtelLogContext = {
  span_id?: string;
};

type ServerOtelLogExporter = {
  emit(record: ServerOtelLogRecord, otelContext?: ServerOtelLogContext): void;
  forceFlush(): Promise<void>;
};

type ServerOtelLogExporterOptions = {
  exporter?: LogRecordExporter;
  diagnosticSink?: (line: string) => void;
};

const OTEL_LOGGER_NAME = "fides-web-server";
const SEVERITY_NUMBER_INFO = 9;
const SEVERITY_NUMBER_WARN = 13;
const SEVERITY_NUMBER_ERROR = 17;

let cachedExporter: ServerOtelLogExporter | undefined;
let exporterErrorLogged = false;

export function emitServerOtelLog(
  record: ServerOtelLogRecord,
  otelContext?: ServerOtelLogContext,
  diagnosticSink?: (line: string) => void,
) {
  try {
    getServerOtelLogExporter(diagnosticSink)?.emit(record, otelContext);
  } catch (error) {
    writeExporterDiagnostic(diagnosticSink, record, error);
  }
}

export function getServerOtelLogExporter(
  diagnosticSink?: (line: string) => void,
): ServerOtelLogExporter | undefined {
  if (!cachedExporter) {
    cachedExporter = createServerOtelLogExporter({ diagnosticSink });
  }
  return cachedExporter;
}

export function resetServerOtelLogExporterForTest() {
  cachedExporter = undefined;
  exporterErrorLogged = false;
}

export function createServerOtelLogExporter(
  options: ServerOtelLogExporterOptions = {},
): ServerOtelLogExporter | undefined {
  const env = getFidesEnv();
  if (env.OTEL_LOGS_EXPORTER !== "otlp") {
    return undefined;
  }

  const exporter =
    options.exporter ??
    new OTLPLogExporter({
      url: env.OTEL_EXPORTER_OTLP_LOGS_ENDPOINT,
      headers: parseHeaders(env.OTEL_EXPORTER_OTLP_LOGS_HEADERS),
    });
  const provider = new LoggerProvider({
    resource: resourceFromAttributes({
      "service.name": env.OTEL_SERVICE_NAME,
      "deployment.environment": getRuntimeEnvironmentFromEnv(),
    }),
    processors: [
      new SimpleLogRecordProcessor(exporter),
    ],
  });
  const logger = provider.getLogger(OTEL_LOGGER_NAME);

  return {
    emit(record, otelContext) {
      const activeContext = buildLogContext(record, otelContext);
      logger.emit({
        context: activeContext,
        timestamp: new Date(record.timestamp),
        severityText: record.level,
        severityNumber: severityNumberForLevel(record.level),
        body: record.operation,
        attributes: {
          ...record,
          "service.name": env.OTEL_SERVICE_NAME,
          "deployment.environment": getRuntimeEnvironmentFromEnv(),
        },
      });
    },
    forceFlush() {
      return provider.forceFlush().catch((error: unknown) => {
        writeExporterDiagnostic(options.diagnosticSink, undefined, error);
      });
    },
  };
}

function writeExporterDiagnostic(
  sink: ((line: string) => void) | undefined,
  sourceRecord: ServerOtelLogRecord | undefined,
  error: unknown,
) {
  if (!sink || exporterErrorLogged) {
    return;
  }
  exporterErrorLogged = true;
  const diagnosticRecord: ServerOtelLogRecord = {
    timestamp: new Date().toISOString(),
    level: "WARN",
    service: sourceRecord?.service ?? "fides-web",
    operation: "server_otel_logs.exporter_error",
    deployment_environment: getRuntimeEnvironmentFromEnv(),
    error_code: "FIDES-OBSERVABILITY-0001",
    error_type: readErrorType(error),
    request_id: sourceRecord?.request_id,
    trace_id: sourceRecord?.trace_id,
  };
  sink(JSON.stringify(diagnosticRecord));
}

function readErrorType(error: unknown): string {
  return error instanceof Error && error.name ? error.name : "OtelLogsExporterError";
}

function buildLogContext(record: ServerOtelLogRecord, otelContext?: ServerOtelLogContext) {
  const spanId = record.span_id ?? otelContext?.span_id;
  if (!record.trace_id || !spanId) {
    return ROOT_CONTEXT;
  }
  const spanContext = {
    traceId: record.trace_id,
    spanId,
    traceFlags: TraceFlags.SAMPLED,
  };
  if (!isSpanContextValid(spanContext)) {
    return ROOT_CONTEXT;
  }
  return trace.setSpanContext(context.active(), spanContext);
}

function severityNumberForLevel(level: ServerOtelLogRecord["level"]): number {
  if (level === "ERROR") {
    return SEVERITY_NUMBER_ERROR;
  }
  if (level === "WARN") {
    return SEVERITY_NUMBER_WARN;
  }
  return SEVERITY_NUMBER_INFO;
}

function parseHeaders(raw: string | undefined): Record<string, string> | undefined {
  if (!raw) {
    return undefined;
  }
  return raw.split(",").reduce<Record<string, string>>((headers, item) => {
    const index = item.indexOf("=");
    const key = index >= 0 ? item.slice(0, index).trim() : "";
    const value = index >= 0 ? item.slice(index + 1).trim() : "";
    if (!key || !value) {
      throw new Error("OTEL_EXPORTER_OTLP_LOGS_HEADERS must use comma-separated key=value pairs");
    }
    headers[key] = value;
    return headers;
  }, {});
}
