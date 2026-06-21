import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { MobileVerificationController } from "@/adapters/mobile-verification/mobile-verification-controller";
import { MobileVerificationScreen } from "./mobile-verification-screen";

describe("MobileVerificationScreen", () => {
  afterEach(() => {
    vi.useRealTimers();
    cleanup();
  });

  it("blocks non-Hong Kong numbers before sending OTP", async () => {
    const controller = createController();
    controller.sendOtp = vi.fn().mockResolvedValue({
      ok: false,
      error: { field: "phone", message: "暂仅支持香港 +852 手机号" },
    });
    render(<MobileVerificationScreen controller={controller} />);

    await userEvent.selectOptions(screen.getByLabelText("Country code"), "+86");
    await userEvent.type(screen.getByLabelText("Mobile Number"), "13800138000");
    await userEvent.click(screen.getByRole("button", { name: "Send" }));

    expect(await screen.findByText("暂仅支持香港 +852 手机号")).toBeTruthy();
    expect(controller.sendOtp).toHaveBeenCalledWith({
      countryCode: "+86",
      phone: "1380 0138",
    });
  });

  it("shows OTP entry and cooldown after sending a valid number", async () => {
    const controller = createController();
    render(<MobileVerificationScreen controller={controller} />);

    await userEvent.type(screen.getByLabelText("Mobile Number"), "9123 4567");
    await userEvent.click(screen.getByRole("button", { name: "Send" }));

    expect(await screen.findByLabelText("OTP digit 1")).toBeTruthy();
    expect(screen.getByRole("button", { name: /Resend in 0:59/ })).toHaveProperty(
      "disabled",
      true,
    );
    expect(controller.sendOtp).toHaveBeenCalledWith({
      countryCode: "+852",
      phone: "9123 4567",
    });
  });

  it("counts cooldown down and allows resending a code", async () => {
    const user = userEvent.setup();
    const controller = createController();
    controller.sendOtp = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
          value: {
            challengeId: "challenge-1",
            expiresInSec: 300,
          resendAfterSec: 1,
        },
      })
      .mockResolvedValueOnce({
        ok: true,
          value: {
            challengeId: "challenge-2",
            expiresInSec: 300,
          resendAfterSec: 1,
        },
      });
    render(<MobileVerificationScreen controller={controller} />);

    await user.type(screen.getByLabelText("Mobile Number"), "9123 4567");
    await user.click(screen.getByRole("button", { name: "Send" }));

    expect(await screen.findByRole("button", { name: /Resend in 0:01/ })).toHaveProperty(
      "disabled",
      true,
    );

    const resend = await screen.findByRole("button", { name: "Resend code" });
    expect(resend).toHaveProperty("disabled", false);
    await user.click(resend);

    await waitFor(() => {
      expect(controller.sendOtp).toHaveBeenCalledTimes(2);
    });
    expect(await screen.findByRole("button", { name: /Resend in 0:01/ })).toHaveProperty(
      "disabled",
      true,
    );
  }, 10000);

  it("keeps the user on the screen when OTP is invalid", async () => {
    const controller = createController();
    controller.verifyOtp = vi.fn().mockResolvedValue({
      ok: false,
      error: { field: "otp", message: "验证码不正确" },
    });
    render(<MobileVerificationScreen controller={controller} />);

    await userEvent.type(screen.getByLabelText("Mobile Number"), "91234567");
    await userEvent.click(screen.getByRole("button", { name: "Send" }));
    await userEvent.type(await screen.findByLabelText("OTP digit 1"), "000000");
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));

    expect(await screen.findByText("验证码不正确")).toBeTruthy();
    expect(screen.getByLabelText("OTP digit 1")).toBe(document.activeElement);
  });

  it("shows expired OTP error and keeps resend path available", async () => {
    const controller = createController();
    controller.verifyOtp = vi.fn().mockResolvedValue({
      ok: false,
      error: { field: "otp", message: "验证码已过期，请重新获取验证码" },
    });
    render(<MobileVerificationScreen controller={controller} />);

    await userEvent.type(screen.getByLabelText("Mobile Number"), "91234567");
    await userEvent.click(screen.getByRole("button", { name: "Send" }));
    await userEvent.type(await screen.findByLabelText("OTP digit 1"), "111111");
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));

    expect(await screen.findByText("验证码已过期，请重新获取验证码")).toBeTruthy();
    expect(screen.getByRole("button", { name: /Resend in 0:59/ })).toBeTruthy();
  });

  it("uses retry cooldown when OTP verification is rate limited", async () => {
    const controller = createController();
    controller.verifyOtp = vi.fn().mockResolvedValue({
      ok: false,
      error: { field: "form", message: "请稍后再试", retryAfterSec: 42 },
    });
    render(<MobileVerificationScreen controller={controller} />);

    await userEvent.type(screen.getByLabelText("Mobile Number"), "91234567");
    await userEvent.click(screen.getByRole("button", { name: "Send" }));
    await userEvent.type(await screen.findByLabelText("OTP digit 1"), "222222");
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));

    expect(await screen.findByText("请稍后再试")).toBeTruthy();
    expect(screen.getByRole("button", { name: /Resend in 0:42/ })).toHaveProperty(
      "disabled",
      true,
    );
  });

  it("supports OTP paste and backspace navigation", async () => {
    const controller = createController();
    render(<MobileVerificationScreen controller={controller} />);

    await userEvent.type(screen.getByLabelText("Mobile Number"), "91234567");
    await userEvent.click(screen.getByRole("button", { name: "Send" }));
    const firstDigit = await screen.findByLabelText("OTP digit 1");

    await userEvent.click(firstDigit);
    await userEvent.paste("123456");

    expect(screen.getByLabelText("OTP digit 1")).toHaveProperty("value", "1");
    expect(screen.getByLabelText("OTP digit 6")).toHaveProperty("value", "6");
    expect(screen.getByLabelText("OTP digit 6")).toBe(document.activeElement);

    await userEvent.keyboard("{Backspace}");

    expect(screen.getByLabelText("OTP digit 6")).toBe(document.activeElement);
    await userEvent.keyboard("{Backspace}");
    expect(screen.getByLabelText("OTP digit 5")).toBe(document.activeElement);
  });

  it("advances only after a successful verify response", async () => {
    const controller = createController();
    const onVerified = vi.fn();
    render(<MobileVerificationScreen controller={controller} onVerified={onVerified} />);

    await userEvent.type(screen.getByLabelText("Mobile Number"), "91234567");
    await userEvent.click(screen.getByRole("button", { name: "Send" }));
    await userEvent.type(await screen.findByLabelText("OTP digit 1"), "123456");
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));

    await waitFor(() => {
      expect(onVerified).toHaveBeenCalledWith({
        accessToken: "access-token",
        applicantId: "applicant-1",
        expiresInSec: 3600,
      });
    });
  });

  it("shows re-verification message when session is expired", async () => {
    const controller = createController();
    controller.verifyOtp = vi.fn().mockResolvedValue({
      ok: false,
      error: { field: "form", message: "请重新验证手机号" },
    });
    render(<MobileVerificationScreen controller={controller} />);

    await userEvent.type(screen.getByLabelText("Mobile Number"), "91234567");
    await userEvent.click(screen.getByRole("button", { name: "Send" }));
    await userEvent.type(await screen.findByLabelText("OTP digit 1"), "123456");
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));

    expect(await screen.findByText("请重新验证手机号")).toBeTruthy();
    expect(screen.getByRole("button", { name: /Resend in 0:59/ })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Continue" })).toBeTruthy();
  });
});

function createController(): MobileVerificationController {
  return {
    sendOtp: vi.fn().mockResolvedValue({
      ok: true,
      value: {
        challengeId: "challenge-1",
        expiresInSec: 300,
        resendAfterSec: 59,
      },
    }),
    verifyOtp: vi.fn().mockResolvedValue({
      ok: true,
      value: {
        accessToken: "access-token",
        applicantId: "applicant-1",
        expiresInSec: 3600,
      },
    }),
  };
}
