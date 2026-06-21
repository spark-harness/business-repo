import type {
  FlowControllerPort,
  VerifiedSession,
} from "@/application/mobile-verification/verified-session";

const NEXT_STEP_KEY = "fides.flow.nextStep";

export class BrowserFlowController implements FlowControllerPort {
  constructor(private readonly storage: Storage | undefined = getSessionStorage()) {}

  async advanceAfterMobileVerified(session: VerifiedSession): Promise<void> {
    this.storage?.setItem(
      NEXT_STEP_KEY,
      JSON.stringify({
        applicantId: session.applicantId,
        step: "loan-request",
      }),
    );
  }

  async returnToMobileVerification(): Promise<void> {
    this.storage?.setItem(
      NEXT_STEP_KEY,
      JSON.stringify({
        step: "mobile-verification",
      }),
    );
  }
}

function getSessionStorage() {
  if (typeof window === "undefined") {
    return undefined;
  }

  return window.sessionStorage;
}
