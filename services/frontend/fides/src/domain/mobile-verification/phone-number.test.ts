import { describe, expect, it } from "vitest";

import { parseHongKongPhoneNumber } from "./phone-number";

describe("parseHongKongPhoneNumber", () => {
  it("accepts a Hong Kong +852 mobile number", () => {
    expect(parseHongKongPhoneNumber("+852", "9123 4567")).toEqual({
      countryCode: "+852",
      localNumber: "91234567",
      masked: "+852 **** 4567",
    });
  });

  it("rejects non-Hong Kong country codes", () => {
    expect(() => parseHongKongPhoneNumber("+86", "13800138000")).toThrow(
      "unsupported_country",
    );
  });

  it("rejects invalid Hong Kong mobile numbers", () => {
    expect(() => parseHongKongPhoneNumber("+852", "1234")).toThrow(
      "invalid_phone_number",
    );
  });
});
