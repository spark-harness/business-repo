import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";

import type { IdentityProfileController } from "@/adapters/identity-profile/identity-profile-controller";
import { IdentityProfileScreen } from "./identity-profile-screen";

describe("IdentityProfileScreen", () => {
  afterEach(() => {
    cleanup();
  });

  it("renders saved profile returned by load", async () => {
    render(<IdentityProfileScreen controller={controller({ loadProfile: true })} />);

    await waitFor(() => {
      expect(screen.getByLabelText("First Name")).toHaveProperty("value", "Ada");
    });
  });

  it("shows field error for invalid HKID and does not save", async () => {
    const fake = controller({});
    render(<IdentityProfileScreen controller={fake} />);

    await userEvent.type(screen.getByLabelText("HKID body"), "A123456");
    await userEvent.type(screen.getByLabelText("HKID check digit"), "4");
    await userEvent.type(screen.getByLabelText("First Name"), "Ada");
    await userEvent.type(screen.getByLabelText("Last Name"), "Lovelace");
    await userEvent.type(screen.getByLabelText("Chinese Name"), "Test Name");
    await userEvent.selectOptions(screen.getByLabelText("Nationality"), "hong_kong");
    await userEvent.type(screen.getByLabelText("Date of Birth"), "1990-01-15");
    await userEvent.click(screen.getByRole("button", { name: "Continue" }));

    expect(await screen.findByText("Enter a valid HKID.")).toBeTruthy();
    expect(fake.saved).toBe(false);
  });
});

function controller(options: { loadProfile?: boolean }): IdentityProfileController & { saved: boolean } {
  return {
    saved: false,
    async load() {
      if (!options.loadProfile) {
        return { ok: true, value: { empty: true } };
      }
      return { ok: true, value: { empty: false, profile: validProfile() } };
    },
    async save() {
      this.saved = true;
      return {
        ok: true,
        value: { profile: validProfile(), currentStep: "identity_information", shouldNavigate: false },
      };
    },
  };
}

function validProfile() {
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
