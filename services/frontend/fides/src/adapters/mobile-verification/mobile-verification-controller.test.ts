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

  it("clears saved session when flow advance fails after verification succeeds", async () => {
    const gateway = createGateway();
    const sessionStore = createSessionStore();
    const flowController = createFlowController();
    flowController.advanceAfterMobileVerified = vi.fn().mockRejectedValue(new Error("flow failed"));
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
      ok: false,
      error: { field: "form", message: "请求失败，请稍后重试", traceId: undefined },
    });
    expect(sessionStore.saveVerifiedSession).toHaveBeenCalled();
    expect(sessionStore.clearVerifiedSession).toHaveBeenCalled();
  });

  it("clears session and returns to mobile verification when verification is unauthorized", async () => {
    const gateway = createGateway();
    gateway.verifyOtp = vi.fn().mockRejectedValue({ code: "unauthorized", traceId: "trace-1" });
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
      ok: false,
      error: { field: "form", message: "请重新验证手机号", traceId: "trace-1" },
    });
    expect(sessionStore.clearVerifiedSession).toHaveBeenCalled();
    expect(flowController.returnToMobileVerification).toHaveBeenCalled();
  });

  it("rotates idempotency keys after completed send and verify intents", async () => {
    const gateway = createGateway();
    gateway.sendOtp = vi.fn().mockResolvedValue({
      challengeId: "challenge-1",
      expiresInSec: 300,
      resendAfterSec: 59,
    });
    const generated = ["send-1", "send-2", "send-3", "verify-1", "verify-2", "verify-3"];
    const controller = createMobileVerificationController(
      gateway,
      new UserIntentIdempotencyKeys(() => generated.shift() ?? "unexpected"),
      createSessionStore(),
      createFlowController(),
    );

    await controller.sendOtp({ countryCode: "+852", phone: "91234567" });
    await controller.sendOtp({ countryCode: "+852", phone: "91234567" });
    await controller.verifyOtp({ challengeId: "challenge-1", code: "123456" });
    await controller.verifyOtp({ challengeId: "challenge-1", code: "654321" });

    expect(gateway.sendOtp).toHaveBeenNthCalledWith(
      1,
      expect.objectContaining({ idempotencyKey: "send-1" }),
    );
    expect(gateway.sendOtp).toHaveBeenNthCalledWith(
      2,
      expect.objectContaining({ idempotencyKey: "send-2" }),
    );
    expect(gateway.verifyOtp).toHaveBeenNthCalledWith(
      1,
      expect.objectContaining({ idempotencyKey: "verify-1" }),
    );
    expect(gateway.verifyOtp).toHaveBeenNthCalledWith(
      2,
      expect.objectContaining({ idempotencyKey: "verify-2" }),
    );
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
