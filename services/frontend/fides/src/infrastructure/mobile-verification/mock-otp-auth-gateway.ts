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
      challengeId: `mock-challenge-${command.phone}`,
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

    const phone = command.challengeId.replace("mock-challenge-", "");
    return {
      accessToken: "mock-access-token",
      refreshToken: "mock-refresh-token",
      applicantId: `mock-applicant-${phone}`,
      expiresInSec: 3600,
      refreshExpiresInSec: 3600,
    };
  }
}
