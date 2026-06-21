import { createMobileVerificationController } from "@/adapters/mobile-verification/mobile-verification-controller";
import { BrowserFlowController } from "@/infrastructure/mobile-verification/browser-flow-controller";
import { BrowserSessionStore } from "@/infrastructure/mobile-verification/browser-session-store";
import { MockOtpAuthGateway } from "@/infrastructure/mobile-verification/mock-otp-auth-gateway";

export function createDefaultMobileVerificationController() {
  return createMobileVerificationController(
    new MockOtpAuthGateway(),
    undefined,
    new BrowserSessionStore(),
    new BrowserFlowController(),
  );
}
