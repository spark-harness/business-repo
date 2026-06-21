import { describe, expect, it } from "vitest";

import { BrowserFlowController } from "./browser-flow-controller";

describe("BrowserFlowController", () => {
  it("records the next step after mobile verification succeeds", async () => {
    const storage = new MemoryStorage();
    const controller = new BrowserFlowController(storage);

    await controller.advanceAfterMobileVerified({
      accessToken: "access-token",
      applicantId: "applicant-1",
      expiresInSec: 3600,
    });

    expect(storage.dump()).toContain("loan-request");
    expect(storage.dump()).toContain("applicant-1");
    expect(storage.dump()).not.toContain("access-token");
  });

  it("records return to mobile verification after session expiry", async () => {
    const storage = new MemoryStorage();
    const controller = new BrowserFlowController(storage);

    await controller.returnToMobileVerification();

    expect(storage.dump()).toContain("mobile-verification");
    expect(storage.dump()).not.toContain("loan-request");
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
