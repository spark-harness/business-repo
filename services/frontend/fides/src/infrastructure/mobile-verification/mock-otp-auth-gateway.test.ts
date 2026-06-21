import { describe, expect, it } from "vitest";

import { MockOtpAuthGateway } from "./mock-otp-auth-gateway";

describe("MockOtpAuthGateway", () => {
  it("sends OTP and returns challenge cooldown data", async () => {
    const gateway = new MockOtpAuthGateway();

    await expect(
      gateway.sendOtp({
        countryCode: "+852",
        phone: "91234567",
        idempotencyKey: "send-key",
      }),
    ).resolves.toEqual({
      challengeId: "mock-challenge-91234567",
      expiresInSec: 300,
      resendAfterSec: 59,
    });
  });

  it("verifies the mock OTP code", async () => {
    const gateway = new MockOtpAuthGateway();

    await expect(
      gateway.verifyOtp({
        challengeId: "mock-challenge-91234567",
        code: "123456",
        idempotencyKey: "verify-key",
      }),
    ).resolves.toMatchObject({
      accessToken: "mock-access-token",
      applicantId: "mock-applicant-91234567",
      expiresInSec: 3600,
    });
  });

  it("rejects invalid mock OTP codes", async () => {
    const gateway = new MockOtpAuthGateway();

    await expect(
      gateway.verifyOtp({
        challengeId: "mock-challenge-91234567",
        code: "000000",
        idempotencyKey: "verify-key",
      }),
    ).rejects.toMatchObject({ code: "code_invalid" });
  });
});
