import { describe, expect, it } from "vitest";

import type { DraftPointer } from "@/application/loan-request/loan-request-gateway";
import { createIdentityProfileController } from "./identity-profile-controller";

describe("IdentityProfileController", () => {
  it("saves identity profile and keeps the user on step 3", async () => {
    const gateway = new FakeGateway();
    const store = new FakeStore({ applicationId: "app_001", currentStep: "loan_request" });
    const controller = createIdentityProfileController(gateway, store, () => "idem-1");

    const result = await controller.save(validInput());

    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.value.shouldNavigate).toBe(false);
      expect(result.value.currentStep).toBe("identity_information");
    }
    expect(gateway.saved?.applicationId).toBe("app_001");
    expect(gateway.saved?.idempotencyKey).toBe("idem-1");
    expect(store.pointer?.currentStep).toBe("identity_information");
  });

  it("returns a recoverable form error when there is no draft pointer", async () => {
    const controller = createIdentityProfileController(new FakeGateway(), new FakeStore(null));

    const result = await controller.save(validInput());

    expect(result).toEqual({
      ok: false,
      error: { field: "form", message: "Complete the loan request first." },
    });
  });
});

class FakeGateway {
  saved?: { applicationId: string; idempotencyKey: string };

  async save(command: { applicationId: string; idempotencyKey: string }) {
    this.saved = command;
    return { profile: validInput(), currentStep: "identity_information" };
  }

  async load() {
    return { empty: true };
  }
}

class FakeStore {
  constructor(public pointer: DraftPointer | null) {}

  async loadDraftPointer() {
    return this.pointer;
  }

  async saveDraftPointer(pointer: DraftPointer) {
    this.pointer = pointer;
  }

  async clearDraftPointer() {
    this.pointer = null;
  }
}

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
