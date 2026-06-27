import { createMobileVerificationController } from "@/adapters/mobile-verification/mobile-verification-controller";
import type {
  OtpAuthGateway,
  RefreshTokenResult,
  SendOtpResult,
  VerifyOtpResult,
} from "@/application/mobile-verification/otp-auth-gateway";
import { BrowserFlowController } from "@/infrastructure/mobile-verification/browser-flow-controller";
import { BrowserSessionStore } from "@/infrastructure/mobile-verification/browser-session-store";
import { MockOtpAuthGateway } from "@/infrastructure/mobile-verification/mock-otp-auth-gateway";
import { RestOtpAuthGateway } from "@/infrastructure/mobile-verification/rest-otp-auth-gateway";
import type { PublicRuntimeConfig } from "@/api/runtime-config/public-runtime-config";

export function createDefaultMobileVerificationController(config: PublicRuntimeConfig) {
  return createMobileVerificationController(
    createOtpGateway(config),
    undefined,
    new BrowserSessionStore(),
    new BrowserFlowController(),
  );
}

function createOtpGateway(config: PublicRuntimeConfig): OtpAuthGateway {
  if (config.otpAdapter === "real") {
    return new RestOtpAuthGateway(config.bffBaseUrl);
  }
  if (config.otpAdapter === "disabled") {
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

  async refreshToken(): Promise<RefreshTokenResult> {
    throw { code: "otp_disabled", message: "OTP is disabled" };
  }
}
