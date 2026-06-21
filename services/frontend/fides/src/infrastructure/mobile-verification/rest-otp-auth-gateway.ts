import type {
  BffOtpError,
  OtpAuthGateway,
  SendOtpCommand,
  SendOtpResult,
  VerifyOtpCommand,
  VerifyOtpResult,
} from "@/application/mobile-verification/otp-auth-gateway";

type Fetcher = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;

export class RestOtpAuthGateway implements OtpAuthGateway {
  constructor(
    private readonly baseUrl = "/api/v1",
    private readonly fetcher: Fetcher = fetch,
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

  private async post<T>(
    path: string,
    body: Record<string, string>,
    idempotencyKey: string,
  ): Promise<T> {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.timeoutMs);
    let response: Response;
    try {
      response = await this.fetcher(`${this.baseUrl}${path}`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Idempotency-Key": idempotencyKey,
        },
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

    const payload = await readJson(response);
    if (!response.ok) {
      throw normalizeBffError(payload, response.status, response.headers);
    }

    return payload as T;
  }
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
