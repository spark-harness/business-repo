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
import {
  defaultFetch,
  generatedClientBasePath,
  readBffErrorEnvelope,
  requestInitWithHeaders,
  retryAfter,
  timeoutFetch,
  type Fetcher,
} from "@/infrastructure/bff/generated-client";
import {
  Configuration,
  FidesBffAuthServiceApi,
  type RefreshTokenResponse,
  ResponseError,
  type SendOtpResponse,
  type VerifyOtpResponse,
} from "@spark-harness/idl-ts-client/vesta/lendora/fides-bff/v1";

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
              init: requestInitWithHeaders(init, baseHeaders(this.idempotencyKey)),
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

function baseHeaders(idempotencyKey: string): Record<string, string> {
  return {
    "Idempotency-Key": idempotencyKey,
  };
}

async function normalizeBffError(error: ResponseError): Promise<BffOtpError> {
  const envelope = await readBffErrorEnvelope(error.response);
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
