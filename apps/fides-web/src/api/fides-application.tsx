"use client";

import { useMemo, useState } from "react";

import { createLoanRequestController } from "@/adapters/loan-request/loan-request-controller";
import { createMobileVerificationController } from "@/adapters/mobile-verification/mobile-verification-controller";
import type { PublicRuntimeConfig } from "@/api/runtime-config/public-runtime-config";
import { BrowserDraftStore } from "@/infrastructure/loan-request/browser-draft-store";
import { RestLoanRequestGateway } from "@/infrastructure/loan-request/rest-loan-request-gateway";
import { BrowserFlowController } from "@/infrastructure/mobile-verification/browser-flow-controller";
import { BrowserSessionStore } from "@/infrastructure/mobile-verification/browser-session-store";
import { MockOtpAuthGateway } from "@/infrastructure/mobile-verification/mock-otp-auth-gateway";
import { RestOtpAuthGateway } from "@/infrastructure/mobile-verification/rest-otp-auth-gateway";
import { MobileVerificationScreen } from "@/presentation/mobile-verification/mobile-verification-screen";
import { LoanRequestScreen } from "@/presentation/loan-request/loan-request-screen";
import type {
  OtpAuthGateway,
  RefreshTokenResult,
  SendOtpResult,
  VerifyOtpResult,
} from "@/application/mobile-verification/otp-auth-gateway";

type FidesStep = "mobile-verification" | "loan-request";

export function FidesApplication({ runtimeConfig }: { runtimeConfig: PublicRuntimeConfig }) {
  const [step, setStep] = useState<FidesStep>("mobile-verification");
  const controllers = useMemo(() => {
    const sessionStore = new BrowserSessionStore();
    const mobileController = createMobileVerificationController(
      createOtpGateway(runtimeConfig),
      undefined,
      sessionStore,
      new BrowserFlowController(),
    );
    const loanController = createLoanRequestController(
      new RestLoanRequestGateway(
        runtimeConfig.bffBaseUrl,
        () => sessionStore.getAccessTokenForRequest(),
      ),
      new BrowserDraftStore(),
    );
    return { mobileController, loanController };
  }, [runtimeConfig]);

  if (step === "loan-request") {
    return <LoanRequestScreen controller={controllers.loanController} />;
  }

  return (
    <MobileVerificationScreen
      controller={controllers.mobileController}
      runtimeConfig={runtimeConfig}
      onVerified={() => setStep("loan-request")}
    />
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
