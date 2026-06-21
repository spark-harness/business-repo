import { describe, expect, it, vi } from "vitest";

import { RestOtpAuthGateway } from "./rest-otp-auth-gateway";

describe("RestOtpAuthGateway", () => {
  it("posts sendOtp with an Idempotency-Key header", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      jsonResponse({
        challengeId: "challenge-1",
        expiresInSec: 300,
        resendAfterSec: 59,
      }),
    );
    const gateway = new RestOtpAuthGateway("/api/v1", fetcher);

    await expect(
      gateway.sendOtp({
        countryCode: "+852",
        phone: "91234567",
        idempotencyKey: "send-key",
      }),
    ).resolves.toEqual({
      challengeId: "challenge-1",
      expiresInSec: 300,
      resendAfterSec: 59,
    });

    expect(fetcher).toHaveBeenCalledWith("/api/v1/auth/otp:send", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Idempotency-Key": "send-key",
      },
      body: JSON.stringify({ countryCode: "+852", phone: "91234567" }),
      signal: expect.any(AbortSignal),
    });
  });

  it("maps BFF error envelopes", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      jsonResponse(
        {
          error: {
            code: "too_many_attempts",
            message: "Too many attempts",
            traceId: "trace-1",
            retryAfterSec: 120,
          },
        },
        429,
      ),
    );
    const gateway = new RestOtpAuthGateway("/api/v1", fetcher);

    await expect(
      gateway.verifyOtp({
        challengeId: "challenge-1",
        code: "123456",
        idempotencyKey: "verify-key",
      }),
    ).rejects.toMatchObject({
      code: "too_many_attempts",
      message: "Too many attempts",
      traceId: "trace-1",
      retryAfterSec: 120,
    });
  });

  it("preserves expired-code error envelopes", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      jsonResponse(
        {
          error: {
            code: "code_expired",
            message: "Expired code",
            traceId: "trace-expired",
          },
        },
        400,
      ),
    );
    const gateway = new RestOtpAuthGateway("/api/v1", fetcher);

    await expect(
      gateway.verifyOtp({
        challengeId: "challenge-1",
        code: "123456",
        idempotencyKey: "verify-key",
      }),
    ).rejects.toMatchObject({
      code: "code_expired",
      message: "Expired code",
      traceId: "trace-expired",
    });
  });

  it("maps bare unauthorized responses to session expiry", async () => {
    const fetcher = vi.fn().mockResolvedValue(jsonResponse(undefined, 401));
    const gateway = new RestOtpAuthGateway("/api/v1", fetcher);

    await expect(
      gateway.verifyOtp({
        challengeId: "challenge-1",
        code: "123456",
        idempotencyKey: "verify-key",
      }),
    ).rejects.toMatchObject({ code: "unauthorized" });
  });

  it("maps bare rate-limit responses with Retry-After", async () => {
    const fetcher = vi.fn().mockResolvedValue(jsonResponse(undefined, 429, { "Retry-After": "33" }));
    const gateway = new RestOtpAuthGateway("/api/v1", fetcher);

    await expect(
      gateway.sendOtp({
        countryCode: "+852",
        phone: "91234567",
        idempotencyKey: "send-key",
      }),
    ).rejects.toMatchObject({
      code: "otp_cooldown_active",
      retryAfterSec: 33,
    });
  });

  it("aborts hung OTP requests", async () => {
    const fetcher = vi.fn(
      (_input: RequestInfo | URL, init?: RequestInit) =>
        new Promise<Response>((_resolve, reject) => {
          init?.signal?.addEventListener("abort", () => {
            reject(new DOMException("Aborted", "AbortError"));
          });
        }),
    );
    const gateway = new RestOtpAuthGateway("/api/v1", fetcher, 1);

    await expect(
      gateway.sendOtp({
        countryCode: "+852",
        phone: "91234567",
        idempotencyKey: "send-key",
      }),
    ).rejects.toMatchObject({ code: "network_timeout" });
  });
});

function jsonResponse(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(body === undefined ? "" : JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...headers },
  });
}
