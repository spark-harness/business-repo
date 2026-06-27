import { afterEach, describe, expect, it, vi } from "vitest";

describe("createDefaultMobileVerificationController", () => {
  afterEach(() => {
    vi.resetModules();
  });

  it("uses the mock gateway from public runtime config by default", async () => {
    vi.resetModules();
    const { createDefaultMobileVerificationController } = await import(
      "./create-mobile-verification-controller"
    );
    const controller = createDefaultMobileVerificationController({
      otpAdapter: "mock",
      bffBaseUrl: "/api/v1",
      browserTracing: { headers: {} },
    });

    await expect(
      controller.sendOtp({ countryCode: "+852", phone: "91234567" }),
    ).resolves.toMatchObject({
      ok: true,
      value: { resendAfterSec: 59 },
    });
  });

  it("can disable OTP through public runtime config", async () => {
    vi.resetModules();
    const { createDefaultMobileVerificationController } = await import(
      "./create-mobile-verification-controller"
    );
    const controller = createDefaultMobileVerificationController({
      otpAdapter: "disabled",
      bffBaseUrl: "/api/v1",
      browserTracing: { headers: {} },
    });

    await expect(
      controller.sendOtp({ countryCode: "+852", phone: "91234567" }),
    ).resolves.toMatchObject({
      ok: false,
      error: { field: "phone", message: "请求失败，请稍后重试" },
    });
  });
});
