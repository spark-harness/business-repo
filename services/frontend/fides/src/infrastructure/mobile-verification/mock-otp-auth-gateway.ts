import type {
  OtpAuthGateway,
  SendOtpCommand,
  SendOtpResult,
  VerifyOtpCommand,
  VerifyOtpResult,
} from "@/application/mobile-verification/otp-auth-gateway";

export class MockOtpAuthGateway implements OtpAuthGateway {
  async sendOtp(command: SendOtpCommand): Promise<SendOtpResult> {
    return {
      challengeId: `mock-challenge-${opaqueId(command.phone)}`,
      expiresInSec: 300,
      resendAfterSec: 59,
    };
  }

  async verifyOtp(command: VerifyOtpCommand): Promise<VerifyOtpResult> {
    if (command.code !== "123456") {
      throw {
        code: "code_invalid",
        message: "Invalid verification code",
      };
    }

    return {
      accessToken: "mock-access-token",
      refreshToken: "mock-refresh-token",
      applicantId: `mock-applicant-${command.challengeId.replace("mock-challenge-", "")}`,
      expiresInSec: 3600,
      refreshExpiresInSec: 3600,
    };
  }
}

function opaqueId(value: string): string {
  let hash = 0;
  for (const character of value) {
    hash = (hash * 31 + character.charCodeAt(0)) >>> 0;
  }
  return hash.toString(36);
}
