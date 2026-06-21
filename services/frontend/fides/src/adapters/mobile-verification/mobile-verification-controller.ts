import type {
  OtpAuthGateway,
  VerifyOtpResult,
} from "@/application/mobile-verification/otp-auth-gateway";
import { mapOtpAuthError } from "@/application/mobile-verification/otp-auth-gateway";
import { UserIntentIdempotencyKeys } from "@/application/mobile-verification/idempotency-key";
import type {
  FlowControllerPort,
  SessionStore,
} from "@/application/mobile-verification/verified-session";
import { parseOtpCode } from "@/domain/mobile-verification/otp-code";
import { parseHongKongPhoneNumber } from "@/domain/mobile-verification/phone-number";

export type MobileVerificationError = {
  field: "phone" | "otp" | "form";
  message: string;
  retryAfterSec?: number;
  traceId?: string;
};

export type MobileVerificationSendResult = {
  challengeId: string;
  expiresInSec: number;
  resendAfterSec: number;
};

export type MobileVerificationVerifiedResult = VerifyOtpResult;

export type MobileVerificationController = {
  sendOtp(input: {
    countryCode: string;
    phone: string;
  }): Promise<
    | { ok: true; value: MobileVerificationSendResult }
    | { ok: false; error: MobileVerificationError }
  >;
  verifyOtp(input: {
    challengeId: string;
    code: string;
  }): Promise<
    | { ok: true; value: MobileVerificationVerifiedResult }
    | { ok: false; error: MobileVerificationError }
  >;
};

export function createMobileVerificationController(
  gateway: OtpAuthGateway,
  keyStore = new UserIntentIdempotencyKeys(),
  sessionStore?: SessionStore,
  flowController?: FlowControllerPort,
): MobileVerificationController {
  return {
    async sendOtp(input) {
      try {
        const phone = parseHongKongPhoneNumber(input.countryCode, input.phone);
        const value = await gateway.sendOtp({
          countryCode: phone.countryCode,
          phone: phone.localNumber,
          idempotencyKey: keyStore.current("send-otp"),
        });
        keyStore.rotate("send-otp");
        return { ok: true, value };
      } catch (error) {
        return {
          ok: false,
          error: toMobileVerificationError(mapControllerError(error), "phone"),
        };
      }
    },

    async verifyOtp(input) {
      try {
        const otp = parseOtpCode(input.code);
        const value = await gateway.verifyOtp({
          challengeId: input.challengeId,
          code: otp.value,
          idempotencyKey: keyStore.current("verify-otp"),
        });
        await sessionStore?.saveVerifiedSession(value);
        try {
          await flowController?.advanceAfterMobileVerified(value);
        } catch (error) {
          await sessionStore?.clearVerifiedSession();
          throw error;
        }
        keyStore.rotate("verify-otp");
        return { ok: true, value };
      } catch (error) {
        const mapped = mapControllerError(error);
        if (mapped.kind === "session_expired") {
          await sessionStore?.clearVerifiedSession();
          await flowController?.returnToMobileVerification();
        }
        return {
          ok: false,
          error: toMobileVerificationError(mapped, "otp"),
        };
      }
    },
  };
}

function mapControllerError(error: unknown): ReturnType<typeof mapOtpAuthError> {
  if (error instanceof Error) {
    if (error.message === "unsupported_country") {
      return { kind: "unsupported_country", message: "暂仅支持香港 +852 手机号" };
    }
    if (error.message === "invalid_phone_number") {
      return { kind: "unsupported_country", message: "请输入有效的香港手机号" };
    }
    if (error.message === "invalid_otp_code") {
      return { kind: "otp_invalid", message: "请输入 6 位验证码" };
    }
  }

  return mapOtpAuthError(normalizeThrownError(error));
}

function toMobileVerificationError(
  mapped: ReturnType<typeof mapOtpAuthError>,
  defaultField: "phone" | "otp",
): MobileVerificationError {
  return {
    field:
      mapped.kind === "otp_invalid" || mapped.kind === "otp_expired"
        ? "otp"
        : mapped.kind === "unsupported_country"
          ? "phone"
          : defaultField === "phone"
            ? "phone"
            : "form",
    message: mapped.message,
    retryAfterSec: mapped.kind === "cooldown" ? mapped.retryAfterSec : undefined,
    traceId: mapped.traceId,
  };
}

function normalizeThrownError(error: unknown) {
  if (error && typeof error === "object" && "code" in error) {
    return error as { code: string; message?: string; traceId?: string; retryAfterSec?: number };
  }

  return { code: "unknown", message: "请求失败，请稍后重试" };
}
