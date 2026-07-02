import { afterEach, describe, expect, it, vi } from "vitest";

import { RestOtpAuthGateway } from "./rest-otp-auth-gateway";

describe("RestOtpAuthGateway", () => {
  afterEach(() => {
    vi.doUnmock("@opentelemetry/sdk-trace-web");
  });

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

  it("uses the browser fetch binding when no fetcher is injected", async () => {
    const originalFetch = globalThis.fetch;
    const fetcher = vi.fn().mockResolvedValue(
      jsonResponse({
        challengeId: "challenge-1",
        expiresInSec: 300,
        resendAfterSec: 59,
      }),
    );
    globalThis.fetch = fetcher;
    const gateway = new RestOtpAuthGateway("/api/v1");

    try {
      await expect(
        gateway.sendOtp({
          countryCode: "+852",
          phone: "91234567",
          idempotencyKey: "send-key",
        }),
      ).resolves.toMatchObject({
        challengeId: "challenge-1",
      });
    } finally {
      globalThis.fetch = originalFetch;
    }

    expect(fetcher).toHaveBeenCalledWith("/api/v1/auth/otp:send", expect.any(Object));
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

  it("posts refreshToken to the BFF refresh route", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      jsonResponse({
        accessToken: "new-access-token",
        expiresInSec: 1800,
      }),
    );
    const gateway = new RestOtpAuthGateway("/api/v1", fetcher);

    await expect(
      gateway.refreshToken({
        refreshToken: "refresh-token",
        idempotencyKey: "refresh-key",
      }),
    ).resolves.toEqual({
      accessToken: "new-access-token",
      expiresInSec: 1800,
    });

    expect(fetcher).toHaveBeenCalledWith("/api/v1/auth/token:refresh", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Idempotency-Key": "refresh-key",
      },
      body: JSON.stringify({ refreshToken: "refresh-token" }),
      signal: expect.any(AbortSignal),
    });
  });

  it("rejects incomplete success envelopes", async () => {
    const fetcher = vi.fn().mockResolvedValue(jsonResponse({ expiresInSec: 300 }));
    const gateway = new RestOtpAuthGateway("/api/v1", fetcher);

    await expect(
      gateway.sendOtp({
        countryCode: "+852",
        phone: "91234567",
        idempotencyKey: "send-key",
      }),
    ).rejects.toMatchObject({
      code: "system_error",
      message: "Incomplete BFF response",
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

  it("continues OTP requests when browser tracing cannot initialize", async () => {
    vi.resetModules();
    vi.doMock("@opentelemetry/sdk-trace-web", async (importOriginal) => {
      const actual = await importOriginal<typeof import("@opentelemetry/sdk-trace-web")>();
      class FailingWebTracerProvider extends actual.WebTracerProvider {
        override register() {
          throw new Error("otel registration failed");
        }
      }
      return {
        ...actual,
        WebTracerProvider: FailingWebTracerProvider,
      };
    });
    const { RestOtpAuthGateway: GatewayWithFailingTracing } = await import("./rest-otp-auth-gateway");
    const fetcher = vi.fn().mockResolvedValue(
      jsonResponse({
        challengeId: "challenge-1",
        expiresInSec: 300,
        resendAfterSec: 59,
      }),
    );
    const gateway = new GatewayWithFailingTracing("/api/v1", fetcher);

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
      headers: expect.objectContaining({
        "Content-Type": "application/json",
        "Idempotency-Key": "send-key",
      }),
      body: JSON.stringify({ countryCode: "+852", phone: "91234567" }),
      signal: expect.any(AbortSignal),
    });
  });
});

function jsonResponse(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(body === undefined ? "" : JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...headers },
  });
}
