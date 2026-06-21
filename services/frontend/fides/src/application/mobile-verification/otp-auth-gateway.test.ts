import { describe, expect, it } from "vitest";

import { mapOtpAuthError } from "./otp-auth-gateway";

describe("mapOtpAuthError", () => {
  it("maps invalid OTP to an inline OTP error", () => {
    expect(mapOtpAuthError({ code: "code_invalid", traceId: "trace-1" })).toEqual({
      kind: "otp_invalid",
      message: "验证码不正确",
      traceId: "trace-1",
    });
  });

  it("maps expired OTP to a resendable OTP error", () => {
    expect(mapOtpAuthError({ code: "code_expired" })).toEqual({
      kind: "otp_expired",
      message: "验证码已过期，请重新获取验证码",
    });
  });

  it("maps cooldown errors to a retry-after state", () => {
    expect(mapOtpAuthError({ code: "otp_cooldown_active", retryAfterSec: 42 })).toEqual({
      kind: "cooldown",
      message: "请稍后再试",
      retryAfterSec: 42,
    });
  });

  it("maps too many attempts to the same retry-after state", () => {
    expect(mapOtpAuthError({ code: "too_many_attempts", retryAfterSec: 120 })).toEqual({
      kind: "cooldown",
      message: "请稍后再试",
      retryAfterSec: 120,
    });
  });

  it("maps unauthorized to session re-verification", () => {
    expect(mapOtpAuthError({ code: "unauthorized", traceId: "trace-401" })).toEqual({
      kind: "session_expired",
      message: "请重新验证手机号",
      traceId: "trace-401",
    });
  });
});
