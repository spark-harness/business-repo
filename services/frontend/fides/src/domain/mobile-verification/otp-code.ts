export type OtpCode = {
  value: string;
};

export function parseOtpCode(rawCode: string): OtpCode {
  const value = rawCode.replace(/\s/g, "");
  if (!/^\d{6}$/.test(value)) {
    throw new Error("invalid_otp_code");
  }

  return { value };
}
