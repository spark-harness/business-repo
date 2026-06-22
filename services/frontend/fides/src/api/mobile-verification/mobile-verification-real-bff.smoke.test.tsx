import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { createMobileVerificationController } from "@/adapters/mobile-verification/mobile-verification-controller";
import { UserIntentIdempotencyKeys } from "@/application/mobile-verification/idempotency-key";
import type {
  FlowControllerPort,
  SessionStore,
  VerifiedSession,
} from "@/application/mobile-verification/verified-session";
import { RestOtpAuthGateway } from "@/infrastructure/mobile-verification/rest-otp-auth-gateway";
import { MobileVerificationScreen } from "@/presentation/mobile-verification/mobile-verification-screen";

const runRealBffSmoke = process.env.LEN43_REAL_BFF_SMOKE === "1";
const describeSmoke = runRealBffSmoke ? describe : describe.skip;

describeSmoke("mobile verification real BFF smoke", () => {
  afterEach(() => {
    cleanup();
  });

  it("sends and verifies OTP through the running fides-bff and applicant-api", async () => {
    const sessionStore = new MemorySessionStore();
    const flowController = new MemoryFlowController();
    const controller = createMobileVerificationController(
      new RestOtpAuthGateway(process.env.LEN43_FIDES_BFF_BASE_URL ?? "http://127.0.0.1:8001/api/v1"),
      new UserIntentIdempotencyKeys(),
      sessionStore,
      flowController,
    );
    const onVerified = vi.fn();

    render(<MobileVerificationScreen controller={controller} onVerified={onVerified} />);

    await userEvent.type(screen.getByLabelText("Mobile Number"), process.env.LEN43_SMOKE_PHONE ?? "91989999");
    await userEvent.click(screen.getByRole("button", { name: "Send" }));
    await userEvent.type(await screen.findByLabelText("OTP digit 1"), "123456");
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));

    await waitFor(() => {
      expect(onVerified).toHaveBeenCalled();
    });
    const verified = onVerified.mock.calls[0]?.[0] as VerifiedSession;
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
