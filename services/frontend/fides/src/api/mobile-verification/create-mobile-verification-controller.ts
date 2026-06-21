import { createMobileVerificationController } from "@/adapters/mobile-verification/mobile-verification-controller";
import type {
  OtpAuthGateway,
  SendOtpResult,
  VerifyOtpResult,
} from "@/application/mobile-verification/otp-auth-gateway";
import { BrowserFlowController } from "@/infrastructure/mobile-verification/browser-flow-controller";
import { BrowserSessionStore } from "@/infrastructure/mobile-verification/browser-session-store";
import { MockOtpAuthGateway } from "@/infrastructure/mobile-verification/mock-otp-auth-gateway";
import { RestOtpAuthGateway } from "@/infrastructure/mobile-verification/rest-otp-auth-gateway";

export function createDefaultMobileVerificationController() {
  return createMobileVerificationController(
    createOtpGateway(),
    undefined,
    new BrowserSessionStore(),
    new BrowserFlowController(),
  );
}

function createOtpGateway(): OtpAuthGateway {
  const mode = process.env.NEXT_PUBLIC_FIDES_OTP_ADAPTER ?? "mock";
  if (mode === "real") {
    return new RestOtpAuthGateway(process.env.NEXT_PUBLIC_FIDES_BFF_BASE_URL ?? "/api/v1");
  }
  if (mode === "disabled") {
    return new DisabledOtpAuthGateway();
  }
  return new MockOtpAuthGateway();
}

class DisabledOtpAuthGateway implements OtpAuthGateway {
  async sendOtp(): Promise<SendOtpResult> {
    throw { code: "otp_disabled", message: "OTP is disabled" };
  }

  async verifyOtp(): Promise<VerifyOtpResult> {
    throw { code: "otp_disabled", message: "OTP is disabled" };
  }
}
