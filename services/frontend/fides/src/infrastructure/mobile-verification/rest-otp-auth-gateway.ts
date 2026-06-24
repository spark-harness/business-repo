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
  Configuration,
  FidesBffAuthServiceApi,
  type RefreshTokenResponse,
  ResponseError,
  type SendOtpResponse,
  type VerifyOtpResponse,
} from "@spark-harness/idl-ts-client/vesta/lendora/fides-bff/v1";
import { activeContext } from "@/infrastructure/observability/browser-tracing";

type Fetcher = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;
type FidesBffApiFactory = (configuration: Configuration) => FidesBffAuthServiceApi;

export class RestOtpAuthGateway implements OtpAuthGateway {
  private readonly client: FidesBffAuthServiceApi;
  private idempotencyKey = "";

  constructor(
    baseUrl = "/api/v1",
    fetcher: Fetcher = defaultFetch,
    timeoutMs = 10000,
    clientFactory: FidesBffApiFactory = (configuration) => new FidesBffAuthServiceApi(configuration),
  ) {
    this.client = clientFactory(
      new Configuration({
        basePath: generatedClientBasePath(baseUrl),
        fetchApi: timeoutFetch(fetcher, timeoutMs) as typeof fetch,
        middleware: [
          {
            pre: async ({ url, init }) => ({
              url,
              init: requestInitWithTraceHeaders(init, this.traceHeaders(this.idempotencyKey)),
            }),
          },
        ],
      }),
    );
  }

  async sendOtp(command: SendOtpCommand): Promise<SendOtpResult> {
    return this.call(command.idempotencyKey, async () =>
      toSendOtpResult(await this.client.fidesBffAuthServiceSendOtp({
        sendOtpRequest: {
          countryCode: command.countryCode,
          phone: command.phone,
        },
      })),
    );
  }

  async verifyOtp(command: VerifyOtpCommand): Promise<VerifyOtpResult> {
    return this.call(command.idempotencyKey, async () =>
      toVerifyOtpResult(await this.client.fidesBffAuthServiceVerifyOtp({
        verifyOtpRequest: {
          challengeId: command.challengeId,
          code: command.code,
        },
      })),
    );
  }

  async refreshToken(command: RefreshTokenCommand): Promise<RefreshTokenResult> {
    return this.call(command.idempotencyKey, async () =>
      toRefreshTokenResult(await this.client.fidesBffAuthServiceRefreshToken({
        refreshTokenRequest: {
          refreshToken: command.refreshToken,
        },
      })),
    );
  }

  private async call<T>(idempotencyKey: string, operation: () => Promise<T>): Promise<T> {
    this.idempotencyKey = idempotencyKey;
    try {
      return await operation();
    } catch (error) {
      if (error instanceof ResponseError) {
        throw await normalizeBffError(error);
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

function generatedClientBasePath(baseUrl: string): string {
  return baseUrl.replace(/\/api\/v1\/?$/, "");
}

function toSendOtpResult(response: SendOtpResponse): SendOtpResult {
  assertString(response.challengeId);
  assertNumber(response.expiresInSec);
  assertNumber(response.resendAfterSec);
  return {
    challengeId: response.challengeId,
    expiresInSec: response.expiresInSec,
    resendAfterSec: response.resendAfterSec,
  };
}

function toVerifyOtpResult(response: VerifyOtpResponse): VerifyOtpResult {
  assertString(response.accessToken);
  assertString(response.applicantId);
  assertNumber(response.expiresInSec);
  return {
    accessToken: response.accessToken,
    refreshToken: response.refreshToken,
    applicantId: response.applicantId,
    expiresInSec: response.expiresInSec,
    refreshExpiresInSec: response.refreshExpiresInSec,
  };
}

function toRefreshTokenResult(response: RefreshTokenResponse): RefreshTokenResult {
  assertString(response.accessToken);
  assertNumber(response.expiresInSec);
  return {
    accessToken: response.accessToken,
    expiresInSec: response.expiresInSec,
  };
}

function assertString(value: unknown): asserts value is string {
  if (typeof value !== "string" || value.length === 0) {
    throw incompleteBffResponse();
  }
}

function assertNumber(value: unknown): asserts value is number {
  if (typeof value !== "number" || !Number.isFinite(value)) {
    throw incompleteBffResponse();
  }
}

function incompleteBffResponse(): BffOtpError {
  return { code: "system_error", message: "Incomplete BFF response" };
}

function requestInitWithTraceHeaders(init: RequestInit, headers: Record<string, string>): RequestInit {
  const nextInit: RequestInit = {
    ...init,
    headers: {
      ...(init.headers as Record<string, string> | undefined),
      ...headers,
    },
  };
  if (nextInit.credentials === undefined) {
    delete nextInit.credentials;
  }
  return nextInit;
}

function timeoutFetch(fetcher: Fetcher, timeoutMs: number): Fetcher {
  return async (input, init) => {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), timeoutMs);
    try {
      return await fetcher(input, {
        ...init,
        signal: controller.signal,
      });
    } finally {
      clearTimeout(timeout);
    }
  };
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

async function normalizeBffError(error: ResponseError): Promise<BffOtpError> {
  const envelope = await readErrorEnvelope(error.response);
  if (envelope.code === "unknown" && error.response.status === 401) {
    return { code: "unauthorized" };
  }
  if (envelope.code === "unknown" && error.response.status === 429) {
    return { code: "otp_cooldown_active", retryAfterSec: retryAfter(error.response.headers) };
  }
  if (envelope.code === "system_error") {
    return { code: "system_error" };
  }
  return {
    code: envelope.code,
    message: envelope.message,
    traceId: envelope.traceId,
    retryAfterSec: envelope.retryAfterSec,
  };
}

async function readErrorEnvelope(response: Response): Promise<BffOtpError> {
  try {
    const payload = (await response.json()) as unknown;
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
        retryAfterSec: typeof error.retryAfterSec === "number" ? error.retryAfterSec : retryAfter(response.headers),
      };
    }
  } catch {
    // Preserve bare HTTP status mapping below.
  }
  return { code: response.status >= 500 ? "system_error" : "unknown", retryAfterSec: retryAfter(response.headers) };
}

function retryAfter(headers: Headers): number | undefined {
  const value = headers.get("Retry-After");
  if (!value) {
    return undefined;
  }
  const seconds = Number(value);
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : undefined;
}
