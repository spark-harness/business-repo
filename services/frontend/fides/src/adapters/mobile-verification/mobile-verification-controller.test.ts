import { describe, expect, it, vi } from "vitest";

import { UserIntentIdempotencyKeys } from "@/application/mobile-verification/idempotency-key";
import type { OtpAuthGateway } from "@/application/mobile-verification/otp-auth-gateway";
import type {
  FlowControllerPort,
  SessionStore,
} from "@/application/mobile-verification/verified-session";
import { createMobileVerificationController } from "./mobile-verification-controller";

describe("createMobileVerificationController", () => {
  it("saves verified session and advances flow after OTP verification succeeds", async () => {
    const gateway = createGateway();
    const sessionStore = createSessionStore();
    const flowController = createFlowController();
    const controller = createMobileVerificationController(
      gateway,
      new UserIntentIdempotencyKeys(() => "key-1"),
      sessionStore,
      flowController,
    );

    const result = await controller.verifyOtp({
      challengeId: "challenge-1",
      code: "123456",
    });

    expect(result).toEqual({
      ok: true,
      value: {
        accessToken: "access-token",
        applicantId: "applicant-1",
        expiresInSec: 3600,
      },
    });
    expect(sessionStore.saveVerifiedSession).toHaveBeenCalledWith({
      accessToken: "access-token",
      applicantId: "applicant-1",
      expiresInSec: 3600,
    });
    expect(flowController.advanceAfterMobileVerified).toHaveBeenCalledWith({
      accessToken: "access-token",
      applicantId: "applicant-1",
      expiresInSec: 3600,
    });
  });
});

function createGateway(): OtpAuthGateway {
  return {
    sendOtp: vi.fn(),
    verifyOtp: vi.fn().mockResolvedValue({
      accessToken: "access-token",
      applicantId: "applicant-1",
      expiresInSec: 3600,
    }),
  };
}

function createSessionStore(): SessionStore {
  return {
    saveVerifiedSession: vi.fn().mockResolvedValue(undefined),
    clearVerifiedSession: vi.fn().mockResolvedValue(undefined),
  };
}

function createFlowController(): FlowControllerPort {
  return {
    advanceAfterMobileVerified: vi.fn().mockResolvedValue(undefined),
    returnToMobileVerification: vi.fn().mockResolvedValue(undefined),
  };
}
