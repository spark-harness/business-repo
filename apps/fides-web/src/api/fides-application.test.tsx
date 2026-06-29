import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";

import { FidesApplication } from "./fides-application";

describe("FidesApplication", () => {
  afterEach(() => {
    cleanup();
    window.sessionStorage.clear();
  });

  it("shows loan request after mobile verification succeeds", async () => {
    render(
      <FidesApplication
        runtimeConfig={{ otpAdapter: "mock", bffBaseUrl: "/api/v1", browserTracing: { headers: {} } }}
      />,
    );

    await userEvent.type(screen.getByLabelText("Mobile Number"), "91234567");
    await userEvent.click(screen.getByRole("button", { name: "Send" }));
    await userEvent.type(await screen.findByLabelText("OTP digit 1"), "123456");
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));

    await waitFor(() => {
      expect(screen.getByRole("heading", { name: "How much do you need?" })).toBeTruthy();
    });
  });

  it("restores identity profile after same-tab refresh with an active draft", async () => {
    window.sessionStorage.setItem(
      "fides.mobileVerification.sessionPointer",
      JSON.stringify({
        applicantId: "applicant-1",
        accessToken: "access-token",
        expiresAt: Date.now() + 60000,
      }),
    );
    window.sessionStorage.setItem(
      "fides.loanRequest.draftPointer",
      JSON.stringify({
        applicationId: "app_1",
        applicantId: "applicant-1",
        currentStep: "identity_information",
      }),
    );

    render(
      <FidesApplication
        runtimeConfig={{ otpAdapter: "mock", bffBaseUrl: "/api/v1", browserTracing: { headers: {} } }}
      />,
    );

    await waitFor(() => {
      expect(screen.getByRole("heading", { name: "Identity information" })).toBeTruthy();
    });
  });

  it("does not restore a draft owned by another applicant", async () => {
    window.sessionStorage.setItem(
      "fides.mobileVerification.sessionPointer",
      JSON.stringify({
        applicantId: "applicant-2",
        accessToken: "access-token",
        expiresAt: Date.now() + 60000,
      }),
    );
    window.sessionStorage.setItem(
      "fides.loanRequest.draftPointer",
      JSON.stringify({
        applicationId: "app_1",
        applicantId: "applicant-1",
        currentStep: "identity_information",
      }),
    );

    render(
      <FidesApplication
        runtimeConfig={{ otpAdapter: "mock", bffBaseUrl: "/api/v1", browserTracing: { headers: {} } }}
      />,
    );

    await waitFor(() => {
      expect(screen.getByRole("heading", { name: "How much do you need?" })).toBeTruthy();
    });
    expect(window.sessionStorage.getItem("fides.loanRequest.draftPointer")).toBeNull();
  });
});
