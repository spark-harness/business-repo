import { afterEach, describe, expect, it, vi } from "vitest";

describe("createDefaultMobileVerificationController", () => {
  const originalMode = process.env.NEXT_PUBLIC_FIDES_OTP_ADAPTER;
  const originalBaseUrl = process.env.NEXT_PUBLIC_FIDES_BFF_BASE_URL;

  afterEach(() => {
    process.env.NEXT_PUBLIC_FIDES_OTP_ADAPTER = originalMode;
    process.env.NEXT_PUBLIC_FIDES_BFF_BASE_URL = originalBaseUrl;
    vi.resetModules();
  });

  it("uses the mock gateway by default", async () => {
    delete process.env.NEXT_PUBLIC_FIDES_OTP_ADAPTER;
    vi.resetModules();
    const { createDefaultMobileVerificationController } = await import(
      "./create-mobile-verification-controller"
    );
    const controller = createDefaultMobileVerificationController();

    await expect(
      controller.sendOtp({ countryCode: "+852", phone: "91234567" }),
    ).resolves.toMatchObject({
      ok: true,
      value: { resendAfterSec: 59 },
    });
  });

  it("can disable OTP through adapter mode config", async () => {
    process.env.NEXT_PUBLIC_FIDES_OTP_ADAPTER = "disabled";
    vi.resetModules();
    const { createDefaultMobileVerificationController } = await import(
      "./create-mobile-verification-controller"
    );
    const controller = createDefaultMobileVerificationController();

    await expect(
      controller.sendOtp({ countryCode: "+852", phone: "91234567" }),
    ).resolves.toMatchObject({
      ok: false,
      error: { field: "phone", message: "请求失败，请稍后重试" },
    });
  });
});
