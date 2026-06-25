import { describe, expect, it } from "vitest";

import { parseOtpCode } from "./otp-code";

describe("parseOtpCode", () => {
  it("accepts exactly six digits", () => {
    expect(parseOtpCode("123456")).toEqual({ value: "123456" });
  });

  it("normalizes pasted codes with spaces", () => {
    expect(parseOtpCode("12 34 56")).toEqual({ value: "123456" });
  });

  it("rejects incomplete or non-numeric codes", () => {
    expect(() => parseOtpCode("12345")).toThrow("invalid_otp_code");
    expect(() => parseOtpCode("12345a")).toThrow("invalid_otp_code");
  });
});
