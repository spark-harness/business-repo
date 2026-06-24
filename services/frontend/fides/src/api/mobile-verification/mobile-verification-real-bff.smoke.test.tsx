import { describe, expect, it } from "vitest";

import { createMobileVerificationController } from "@/adapters/mobile-verification/mobile-verification-controller";
import { UserIntentIdempotencyKeys } from "@/application/mobile-verification/idempotency-key";
import type {
  FlowControllerPort,
  SessionStore,
  VerifiedSession,
} from "@/application/mobile-verification/verified-session";
import { RestOtpAuthGateway } from "@/infrastructure/mobile-verification/rest-otp-auth-gateway";

const runRealBffSmoke = process.env.LEN43_REAL_BFF_SMOKE === "1";
const describeSmoke = runRealBffSmoke ? describe : describe.skip;

describeSmoke("mobile verification real BFF smoke", () => {
  it("sends and verifies OTP through the running fides-bff and applicant-api", async () => {
    const sessionStore = new MemorySessionStore();
    const flowController = new MemoryFlowController();
    const controller = createMobileVerificationController(
      new RestOtpAuthGateway(process.env.LEN43_FIDES_BFF_BASE_URL ?? "http://127.0.0.1:8001/api/v1"),
      new UserIntentIdempotencyKeys(),
      sessionStore,
      flowController,
    );
    const phone = process.env.LEN43_SMOKE_PHONE ?? "91989999";

    const sent = await controller.sendOtp({ countryCode: "+852", phone });
    expect(sent).toMatchObject({ ok: true });
    if (!sent.ok) {
      throw new Error(sent.error.message);
    }

    const verifiedResult = await controller.verifyOtp({
      challengeId: sent.value.challengeId,
      code: "123456",
    });
    expect(verifiedResult).toMatchObject({ ok: true });
    if (!verifiedResult.ok) {
      throw new Error(verifiedResult.error.message);
    }

    const verified = verifiedResult.value;
    expect(verified.accessToken).toEqual(expect.any(String));
    expect(verified.applicantId).toMatch(/^applicant_/);
    expect(sessionStore.saved?.applicantId).toBe(verified.applicantId);
    expect(flowController.nextStep?.step).toBe("loan-request");
  }, 15000);
});

class MemorySessionStore implements SessionStore {
  saved: VerifiedSession | undefined;

  async saveVerifiedSession(session: VerifiedSession): Promise<void> {
    this.saved = session;
  }

  async clearVerifiedSession(): Promise<void> {
    this.saved = undefined;
  }
}

class MemoryFlowController implements FlowControllerPort {
  nextStep: { applicantId?: string; step: string } | undefined;

  async advanceAfterMobileVerified(session: VerifiedSession): Promise<void> {
    this.nextStep = { applicantId: session.applicantId, step: "loan-request" };
  }

  async returnToMobileVerification(): Promise<void> {
    this.nextStep = { step: "mobile-verification" };
  }
}
