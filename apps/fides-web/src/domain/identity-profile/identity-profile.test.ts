import { describe, expect, it, vi } from "vitest";

import { normalizeIdentityProfile, validateIdentityProfile } from "./identity-profile";

describe("identity profile validation", () => {
  it("accepts a valid identity profile", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-06-28T04:00:00Z"));

    expect(validateIdentityProfile(validInput())).toBeNull();
    expect(normalizeIdentityProfile(validInput()).hkidBody).toBe("A123456");

    vi.useRealTimers();
  });

  it("rejects an invalid HKID check digit", () => {
    expect(validateIdentityProfile({ ...validInput(), hkidCheckDigit: "4" })).toEqual({
      field: "hkidBody",
      message: "Enter a valid HKID.",
    });
  });

  it("rejects non-English first and last names", () => {
    expect(validateIdentityProfile({ ...validInput(), firstName: "Ada1" })?.field).toBe("firstName");
    expect(validateIdentityProfile({ ...validInput(), lastName: "陳" })?.field).toBe("lastName");
  });

  it("rejects age outside the allowed range", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-06-28T04:00:00Z"));

    expect(validateIdentityProfile({ ...validInput(), dateOfBirth: "2008-06-29" })?.field).toBe("dateOfBirth");
    expect(validateIdentityProfile({ ...validInput(), dateOfBirth: "1965-06-28" })?.field).toBe("dateOfBirth");

    vi.useRealTimers();
  });
});

function validInput() {
  return {
    hkidBody: "A123456",
    hkidCheckDigit: "3",
    firstName: "Ada",
    lastName: "Lovelace",
    chineseName: "Test Name",
    nationality: "hong_kong" as const,
    dateOfBirth: "1990-01-15",
  };
}
