export type SendOtpCommand = {
  countryCode: "+852";
  phone: string;
  idempotencyKey: string;
};

export type SendOtpResult = {
  challengeId: string;
  expiresInSec: number;
  resendAfterSec: number;
};

export type VerifyOtpCommand = {
  challengeId: string;
  code: string;
  idempotencyKey: string;
};

export type VerifyOtpResult = {
  accessToken: string;
  refreshToken?: string;
  applicantId: string;
  expiresInSec: number;
  refreshExpiresInSec?: number;
};

export type BffOtpError = {
  code: string;
  message?: string;
  traceId?: string;
  retryAfterSec?: number;
};

export type OtpAuthUiError =
  | {
      kind: "otp_invalid";
      message: string;
      traceId?: string;
    }
  | {
      kind: "otp_expired";
      message: string;
      traceId?: string;
    }
  | {
      kind: "cooldown";
      message: string;
      retryAfterSec: number;
      traceId?: string;
    }
  | {
      kind: "session_expired";
      message: string;
      traceId?: string;
    }
  | {
      kind: "unsupported_country";
      message: string;
      traceId?: string;
    }
  | {
      kind: "unknown";
      message: string;
      traceId?: string;
    };

export interface OtpAuthGateway {
  sendOtp(command: SendOtpCommand): Promise<SendOtpResult>;
  verifyOtp(command: VerifyOtpCommand): Promise<VerifyOtpResult>;
}

export function mapOtpAuthError(error: BffOtpError): OtpAuthUiError {
  switch (error.code) {
    case "code_invalid":
      return {
        kind: "otp_invalid",
        message: "验证码不正确",
        traceId: error.traceId,
      };
    case "code_expired":
      return {
        kind: "otp_expired",
        message: "验证码已过期，请重新获取验证码",
        traceId: error.traceId,
      };
    case "too_many_attempts":
    case "otp_cooldown_active":
      return {
        kind: "cooldown",
        message: "请稍后再试",
        retryAfterSec: error.retryAfterSec ?? 0,
        traceId: error.traceId,
      };
    case "unauthorized":
      return {
        kind: "session_expired",
        message: "请重新验证手机号",
        traceId: error.traceId,
      };
    case "unsupported_country":
      return {
        kind: "unsupported_country",
        message: "暂仅支持香港 +852 手机号",
        traceId: error.traceId,
      };
    default:
      return {
        kind: "unknown",
        message: error.message ?? "请求失败，请稍后重试",
        traceId: error.traceId,
      };
  }
}
