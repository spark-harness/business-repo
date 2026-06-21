import { describe, expect, it } from "vitest";

import { MockOtpAuthGateway } from "./mock-otp-auth-gateway";

describe("MockOtpAuthGateway", () => {
  it("sends OTP and returns challenge cooldown data", async () => {
    const gateway = new MockOtpAuthGateway();

    const result = await gateway.sendOtp({
      countryCode: "+852",
      phone: "91234567",
      idempotencyKey: "send-key",
    });

    expect(result).toMatchObject({
      expiresInSec: 300,
      resendAfterSec: 59,
    });
    expect(result.challengeId).toMatch(/^mock-challenge-/);
    expect(result.challengeId).not.toContain("91234567");
  });

  it("verifies the mock OTP code without exposing phone in applicant id", async () => {
    const gateway = new MockOtpAuthGateway();
    const sent = await gateway.sendOtp({
      countryCode: "+852",
      phone: "91234567",
      idempotencyKey: "send-key",
    });

    await expect(
      gateway.verifyOtp({
        challengeId: sent.challengeId,
        code: "123456",
        idempotencyKey: "verify-key",
      }),
    ).resolves.toMatchObject({
      accessToken: "mock-access-token",
      expiresInSec: 3600,
    });
    const verified = await gateway.verifyOtp({
      challengeId: sent.challengeId,
      code: "123456",
      idempotencyKey: "verify-key",
    });
    expect(verified.applicantId).toMatch(/^mock-applicant-/);
    expect(verified.applicantId).not.toContain("91234567");
  });

  it("verifies legacy opaque mock challenge ids", async () => {
    const gateway = new MockOtpAuthGateway();

    await expect(
      gateway.sendOtp({
        countryCode: "+852",
        phone: "91234567",
        idempotencyKey: "send-key",
      }),
    ).resolves.toMatchObject({ challengeId: expect.stringMatching(/^mock-challenge-/) });
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
