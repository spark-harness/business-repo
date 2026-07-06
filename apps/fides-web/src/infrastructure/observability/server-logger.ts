import { randomUUID } from "node:crypto";

import { isSpanContextValid, trace } from "@opentelemetry/api";

import { getRuntimeEnvironmentFromEnv } from "@/config/env";
import { emitServerOtelLog, type ServerOtelLogContext } from "./server-otel-logs";

type ServerLogLevel = "INFO" | "WARN" | "ERROR";

export type ServerLogField = string | number | boolean | undefined;

const OTEL_LOG_CONTEXT = Symbol("fides.serverLogContext");

type ServerLogFields = Record<string, ServerLogField> & {
  [OTEL_LOG_CONTEXT]?: ServerOtelLogContext;
};

type ServerLoggerOptions = {
  sink?: (line: string) => void;
};

type RequestLogContext = {
  trace_id?: string;
  span_id?: string;
  request_id: string;
  [OTEL_LOG_CONTEXT]?: ServerOtelLogContext;
};

const SERVICE_NAME = "fides-web";
const TRACEPARENT_PATTERN = /^[\da-f]{2}-([\da-f]{32})-([\da-f]{16})-[\da-f]{2}$/i;
const SAFE_REQUEST_ID_PATTERN = /^[A-Za-z0-9._:-]{1,80}$/;
const PHONE_LIKE_PATTERN = /^\+?\d{7,15}$/;
const TOKEN_LIKE_PATTERN = /bearer|secret|token/i;
const ALLOWED_FIELDS = new Set([
  "config_source",
  "deployment_environment",
  "error_code",
  "error_type",
  "latency_ms",
  "request_id",
  "route",
  "span_id",
  "status",
  "trace_id",
]);
const SENSITIVE_FIELDS = new Set([
  "authorization",
  "body",
  "cookie",
  "headers",
  "otp",
  "password",
  "phone",
  "request_body",
  "response_body",
  "secret",
  "token",
]);

export const serverLogger = createServerLogger();

export function createServerLogger(options: ServerLoggerOptions = {}) {
  const sink = options.sink ?? ((line: string) => console.log(line));

  return {
    info(operation: string, fields: ServerLogFields = {}) {
      writeLog(sink, "INFO", operation, fields);
    },
    warn(operation: string, fields: ServerLogFields = {}) {
      writeLog(sink, "WARN", operation, fields);
    },
    error(operation: string, fields: ServerLogFields = {}) {
      writeLog(sink, "ERROR", operation, fields);
    },
  };
}

export function createRequestLogContext(headers: Headers): RequestLogContext {
  const activeSpanContext = trace.getActiveSpan()?.spanContext();
  const activeTrace =
    activeSpanContext && isSpanContextValid(activeSpanContext)
      ? {
          trace_id: activeSpanContext.traceId,
          span_id: activeSpanContext.spanId,
        }
      : parseTraceparent(headers.get("traceparent"));
  const requestId = readSafeRequestId(headers.get("x-request-id")) || `req_${randomUUID()}`;

  return {
    ...activeTrace,
    request_id: requestId,
  };
}

function writeLog(
  sink: (line: string) => void,
  level: ServerLogLevel,
  operation: string,
  fields: ServerLogFields,
) {
  const sanitizedFields = sanitizeFields(fields);
  const record = {
    timestamp: new Date().toISOString(),
    level,
    service: SERVICE_NAME,
    operation,
    deployment_environment: getRuntimeEnvironmentFromEnv(),
    ...sanitizedFields,
  };

  sink(JSON.stringify(record));
  emitServerOtelLog(record, fields[OTEL_LOG_CONTEXT], sink);
}

function sanitizeFields(fields: ServerLogFields): ServerLogFields {
  return Object.fromEntries(
    Object.entries(fields)
      .filter(([, value]) => value !== undefined)
      .map(([key, value]) => {
        if (!ALLOWED_FIELDS.has(key) || SENSITIVE_FIELDS.has(key.toLowerCase())) {
          throw new Error(`Server log field is not allowed: ${key}`);
        }
        return [key, value];
      }),
  );
}

function readSafeRequestId(value: string | null): string | undefined {
  const requestId = value?.trim();
  if (
    !requestId ||
    !SAFE_REQUEST_ID_PATTERN.test(requestId) ||
    PHONE_LIKE_PATTERN.test(requestId) ||
    TOKEN_LIKE_PATTERN.test(requestId)
  ) {
    return undefined;
  }
  return requestId;
}

function parseTraceparent(value: string | null): Pick<RequestLogContext, "trace_id" | typeof OTEL_LOG_CONTEXT> {
  const match = value?.match(TRACEPARENT_PATTERN);
  if (!match) {
    return {};
  }
  const [, traceId, spanId] = match;
  if (traceId === "00000000000000000000000000000000" || spanId === "0000000000000000") {
    return {};
  }
  return {
    trace_id: traceId.toLowerCase(),
    [OTEL_LOG_CONTEXT]: {
      span_id: spanId.toLowerCase(),
    },
  };
}
