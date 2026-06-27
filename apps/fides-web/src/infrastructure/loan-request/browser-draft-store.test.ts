import { describe, expect, it } from "vitest";

import { BrowserDraftStore } from "./browser-draft-store";

describe("BrowserDraftStore", () => {
  it("stores only non-sensitive draft pointer data", async () => {
    const storage = new MemoryStorage();
    const store = new BrowserDraftStore(storage);

    await store.saveDraftPointer({
      applicationId: "app_1",
      applicantId: "applicant_1",
      currentStep: "loan_request",
      accessToken: "access-token",
    });

    expect(await store.loadDraftPointer()).toEqual({
      applicationId: "app_1",
      applicantId: "applicant_1",
      currentStep: "loan_request",
    });
    expect(storage.dump()).not.toContain("access-token");
  });
});

class MemoryStorage implements Storage {
  private readonly values = new Map<string, string>();

  get length() {
    return this.values.size;
  }

  clear(): void {
    this.values.clear();
  }

  getItem(key: string): string | null {
    return this.values.get(key) ?? null;
  }

  key(index: number): string | null {
    return Array.from(this.values.keys())[index] ?? null;
  }

  removeItem(key: string): void {
    this.values.delete(key);
  }

  setItem(key: string, value: string): void {
    this.values.set(key, value);
  }

  dump(): string {
    return JSON.stringify(Object.fromEntries(this.values));
  }
}

