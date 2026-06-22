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
import { activeContext } from "@/infrastructure/observability/browser-tracing";

type Fetcher = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;

export class RestOtpAuthGateway implements OtpAuthGateway {
  constructor(
    private readonly baseUrl = "/api/v1",
    private readonly fetcher: Fetcher = defaultFetch,
    private readonly timeoutMs = 10000,
  ) {}

  async sendOtp(command: SendOtpCommand): Promise<SendOtpResult> {
    return this.post<SendOtpResult>(
      "/auth/otp:send",
      {
        countryCode: command.countryCode,
        phone: command.phone,
      },
      command.idempotencyKey,
    );
  }

  async verifyOtp(command: VerifyOtpCommand): Promise<VerifyOtpResult> {
    return this.post<VerifyOtpResult>(
      "/auth/otp:verify",
      {
        challengeId: command.challengeId,
        code: command.code,
      },
      command.idempotencyKey,
    );
  }

  async refreshToken(command: RefreshTokenCommand): Promise<RefreshTokenResult> {
    return this.post<RefreshTokenResult>(
      "/auth/token:refresh",
      {
        refreshToken: command.refreshToken,
      },
      command.idempotencyKey,
    );
  }

  private async post<T>(
    path: string,
    body: Record<string, string>,
    idempotencyKey: string,
  ): Promise<T> {
    const response = await this.postWithOptionalTracing(path, body, idempotencyKey);

    const payload = await readJson(response);
    if (!response.ok) {
      throw normalizeBffError(payload, response.status, response.headers);
    }

    return payload as T;
  }

  private async postWithOptionalTracing<T>(
    path: string,
    body: Record<string, string>,
    idempotencyKey: string,
  ): Promise<Response> {
    try {
      const baseContext = activeContext();
      const tracer = trace.getTracer("fides.mobile-verification");
      const span = tracer.startSpan(`POST ${path}`);
      const requestContext = trace.setSpan(baseContext, span);
      return await context.with(requestContext, async () => {
        try {
          return await this.fetchWithTimeout(path, body, traceHeaders(idempotencyKey, requestContext));
        } finally {
          span.end();
        }
      });
    } catch (error) {
      if (isNetworkError(error)) {
        throw error;
      }
      return this.fetchWithTimeout(path, body, baseHeaders(idempotencyKey));
    }
  }

  private async fetchWithTimeout(
    path: string,
    body: Record<string, string>,
    headers: Record<string, string>,
  ): Promise<Response> {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      return await this.fetcher(`${this.baseUrl}${path}`, {
        method: "POST",
        headers,
        body: JSON.stringify(body),
        signal: controller.signal,
      });
    } catch (error) {
      if (error instanceof DOMException && error.name === "AbortError") {
        throw { code: "network_timeout", message: "Request timed out" };
      }
      throw error;
    } finally {
      clearTimeout(timeout);
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
    "Content-Type": "application/json",
    "Idempotency-Key": idempotencyKey,
  };
}

function isNetworkError(error: unknown): boolean {
  return (
    error instanceof DOMException ||
    (error !== null &&
      typeof error === "object" &&
      "code" in error &&
      typeof (error as { code?: unknown }).code === "string")
  );
}

async function readJson(response: Response): Promise<unknown> {
  try {
    return await response.json();
  } catch {
    return undefined;
  }
}

function normalizeBffError(
  payload: unknown,
  status: number,
  headers: Headers,
): BffOtpError {
  if (
    payload &&
    typeof payload === "object" &&
    "error" in payload &&
    payload.error &&
    typeof payload.error === "object"
  ) {
    const error = payload.error as Record<string, unknown>;
    return {
      code: String(error.code ?? "unknown"),
      message: typeof error.message === "string" ? error.message : undefined,
      traceId: typeof error.traceId === "string" ? error.traceId : undefined,
      retryAfterSec:
        typeof error.retryAfterSec === "number" ? error.retryAfterSec : undefined,
    };
  }

  if (status === 401) {
    return { code: "unauthorized" };
  }
  if (status === 429) {
    return {
      code: "otp_cooldown_active",
      retryAfterSec: parseRetryAfter(headers.get("Retry-After")),
    };
  }
  if (status >= 500) {
    return { code: "system_error" };
  }

  return { code: "unknown", message: "Request failed" };
}

function parseRetryAfter(value: string | null): number | undefined {
  if (!value) {
    return undefined;
  }
  const seconds = Number(value);
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : undefined;
}
