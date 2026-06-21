import type { VerifyOtpResult } from "./otp-auth-gateway";

export type VerifiedSession = VerifyOtpResult;

export interface SessionStore {
  saveVerifiedSession(session: VerifiedSession): Promise<void>;
  clearVerifiedSession(): Promise<void>;
}

export interface FlowControllerPort {
  advanceAfterMobileVerified(session: VerifiedSession): Promise<void>;
  returnToMobileVerification(): Promise<void>;
}
