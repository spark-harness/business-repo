import type {
  BffOtpError,
  OtpAuthGateway,
  RefreshTokenCommand,
  RefreshTokenResult,
  SendOtpCommand,
  SendOtpResult,
  VerifyOtpCommand,
  VerifyOtpResult,
} from "@/application/mobile-verification/otp-auth-gateway";
import { context, trace } from "@opentelemetry/api";
import type { Context } from "@opentelemetry/api";
import { W3CTraceContextPropagator } from "@opentelemetry/core";
import {
  FidesBffClient,
  FidesBffClientError,
  type FidesBffClientOptions,
} from "@spark-harness/fides-bff-client";
import { activeContext } from "@/infrastructure/observability/browser-tracing";

type Fetcher = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;

export class RestOtpAuthGateway implements OtpAuthGateway {
  private readonly client: FidesBffClient;
  private idempotencyKey = "";

  constructor(
    baseUrl = "/api/v1",
    fetcher: Fetcher = defaultFetch,
    timeoutMs = 10000,
    clientFactory: (options: FidesBffClientOptions) => FidesBffClient = (options) => new FidesBffClient(options),
  ) {
    this.client = clientFactory({
      baseUrl,
      fetcher: fetcher as typeof fetch,
      timeoutMs,
      headers: () => this.traceHeaders(this.idempotencyKey),
    });
  }

  async sendOtp(command: SendOtpCommand): Promise<SendOtpResult> {
    return this.call(command.idempotencyKey, () =>
      this.client.sendOtp({
        countryCode: command.countryCode,
        phone: command.phone,
      }),
    );
  }

  async verifyOtp(command: VerifyOtpCommand): Promise<VerifyOtpResult> {
    return this.call(command.idempotencyKey, () =>
      this.client.verifyOtp({
        challengeId: command.challengeId,
        code: command.code,
      }),
    );
  }

  async refreshToken(command: RefreshTokenCommand): Promise<RefreshTokenResult> {
    return this.call(command.idempotencyKey, () =>
      this.client.refreshToken({
        refreshToken: command.refreshToken,
      }),
    );
  }

  private async call<T>(idempotencyKey: string, operation: () => Promise<T>): Promise<T> {
    this.idempotencyKey = idempotencyKey;
    try {
      return await operation();
    } catch (error) {
      if (error instanceof FidesBffClientError) {
        throw normalizeBffError(error);
      }
      if (error instanceof DOMException && error.name === "AbortError") {
        throw { code: "network_timeout", message: "Request timed out" };
      }
      throw error;
    } finally {
      this.idempotencyKey = "";
    }
  }

  private traceHeaders(idempotencyKey: string): Record<string, string> {
    try {
      const baseContext = activeContext();
      const tracer = trace.getTracer("fides.mobile-verification");
      const span = tracer.startSpan("POST fides-bff");
      const requestContext = trace.setSpan(baseContext, span);
      return context.with(requestContext, () => {
        try {
          return traceHeaders(idempotencyKey, requestContext);
        } finally {
          span.end();
        }
      });
    } catch {
      return baseHeaders(idempotencyKey);
    }
  }
}

function defaultFetch(input: RequestInfo | URL, init?: RequestInit) {
  return fetch(input, init);
}

function traceHeaders(idempotencyKey: string, requestContext: Context): Record<string, string> {
  const headers = baseHeaders(idempotencyKey);
  try {
    new W3CTraceContextPropagator().inject(requestContext, headers, {
      set(carrier, key, value) {
        carrier[key] = value;
      },
    });
  } catch {
    return headers;
  }
  const traceId = trace.getSpanContext(requestContext)?.traceId;
  if (traceId) {
    headers["X-Trace-Id"] = traceId;
  }
  return headers;
}

function baseHeaders(idempotencyKey: string): Record<string, string> {
  return {
    "Idempotency-Key": idempotencyKey,
  };
}

function normalizeBffError(error: FidesBffClientError): BffOtpError {
  if (error.error.code === "unknown" && error.status === 401) {
    return { code: "unauthorized" };
  }
  if (error.error.code === "unknown" && error.status === 429) {
    return { code: "otp_cooldown_active", retryAfterSec: retryAfter(error.headers) };
  }
  if (error.error.code === "system_error") {
    return { code: "system_error" };
  }
  return {
    code: error.error.code,
    message: error.error.message,
    traceId: error.error.traceId,
    retryAfterSec: error.error.retryAfterSec,
  };
}

function retryAfter(headers: Headers): number | undefined {
  const value = headers.get("Retry-After");
  if (!value) {
    return undefined;
  }
  const seconds = Number(value);
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : undefined;
}
