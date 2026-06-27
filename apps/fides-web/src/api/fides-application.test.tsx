import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { FidesApplication } from "./fides-application";

describe("FidesApplication", () => {
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
});

