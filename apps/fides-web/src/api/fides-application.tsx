"use client";

import { useMemo, useState } from "react";

import { createIdentityProfileController } from "@/adapters/identity-profile/identity-profile-controller";
import { createLoanRequestController } from "@/adapters/loan-request/loan-request-controller";
import { createMobileVerificationController } from "@/adapters/mobile-verification/mobile-verification-controller";
import type { PublicRuntimeConfig } from "@/api/runtime-config/public-runtime-config";
import { RestIdentityProfileGateway } from "@/infrastructure/identity-profile/rest-identity-profile-gateway";
import { BrowserDraftStore } from "@/infrastructure/loan-request/browser-draft-store";
import { RestLoanRequestGateway } from "@/infrastructure/loan-request/rest-loan-request-gateway";
import { BrowserFlowController } from "@/infrastructure/mobile-verification/browser-flow-controller";
import { BrowserSessionStore } from "@/infrastructure/mobile-verification/browser-session-store";
import { MockOtpAuthGateway } from "@/infrastructure/mobile-verification/mock-otp-auth-gateway";
import { RestOtpAuthGateway } from "@/infrastructure/mobile-verification/rest-otp-auth-gateway";
import { IdentityProfileScreen } from "@/presentation/identity-profile/identity-profile-screen";
import { MobileVerificationScreen } from "@/presentation/mobile-verification/mobile-verification-screen";
import { LoanRequestScreen } from "@/presentation/loan-request/loan-request-screen";
import type {
  OtpAuthGateway,
  RefreshTokenResult,
  SendOtpResult,
  VerifyOtpResult,
} from "@/application/mobile-verification/otp-auth-gateway";

type FidesStep = "mobile-verification" | "loan-request" | "identity-profile";

export function FidesApplication({ runtimeConfig }: { runtimeConfig: PublicRuntimeConfig }) {
  const sessionStore = useMemo(() => new BrowserSessionStore(), []);
  const draftStore = useMemo(() => new BrowserDraftStore(), []);
  const [step, setStep] = useState<FidesStep>(() => resolveInitialStep(sessionStore, draftStore));
  const controllers = useMemo(() => {
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
      draftStore,
      undefined,
      () => sessionStore.getApplicantIdForRequest(),
    );
    const identityProfileController = createIdentityProfileController(
      new RestIdentityProfileGateway(
        runtimeConfig.bffBaseUrl,
        () => sessionStore.getAccessTokenForRequest(),
      ),
      draftStore,
    );
    return { mobileController, loanController, identityProfileController };
  }, [draftStore, runtimeConfig, sessionStore]);

  if (step === "identity-profile") {
    return <IdentityProfileScreen controller={controllers.identityProfileController} />;
  }

  if (step === "loan-request") {
    return (
      <LoanRequestScreen
        controller={controllers.loanController}
        onContinue={() => setStep("identity-profile")}
      />
    );
  }

  return (
    <MobileVerificationScreen
      controller={controllers.mobileController}
      runtimeConfig={runtimeConfig}
      onVerified={() => setStep("loan-request")}
    />
  );
}

function resolveInitialStep(sessionStore: BrowserSessionStore, draftStore: BrowserDraftStore): FidesStep {
  if (!sessionStore.getAccessTokenForRequest()) {
    return "mobile-verification";
  }

  const draftPointer = draftStore.loadStoredDraftPointer();
  const applicantId = sessionStore.getApplicantIdForRequest();
  if (draftPointer?.applicantId && applicantId && draftPointer.applicantId !== applicantId) {
    void draftStore.clearDraftPointer();
    return "loan-request";
  }
  if (draftPointer?.currentStep === "identity_information") {
    return "identity-profile";
  }
  if (draftPointer?.currentStep === "loan_request") {
    return "loan-request";
  }
  return "mobile-verification";
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
