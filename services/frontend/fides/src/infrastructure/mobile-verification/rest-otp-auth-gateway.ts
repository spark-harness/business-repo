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
    const response = await this.fetcher(`${this.baseUrl}${path}`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Idempotency-Key": idempotencyKey,
      },
      body: JSON.stringify(body),
    });

    const payload = await response.json();
    if (!response.ok) {
      throw normalizeBffError(payload);
    }

    return payload as T;
  }
}

function normalizeBffError(payload: unknown): BffOtpError {
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

  return { code: "unknown", message: "Request failed" };
}
