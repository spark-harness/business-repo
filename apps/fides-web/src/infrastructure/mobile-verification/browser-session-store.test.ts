import { describe, expect, it } from "vitest";

import { BrowserSessionStore } from "./browser-session-store";

describe("BrowserSessionStore", () => {
  it("persists the short-lived access token for same-tab refreshes", async () => {
    const storage = new MemoryStorage();
    const store = new BrowserSessionStore(storage);

    await store.saveVerifiedSession({
      accessToken: "access-token",
      refreshToken: "refresh-token",
      applicantId: "applicant-1",
      expiresInSec: 3600,
      refreshExpiresInSec: 3600,
    });

    expect(store.getAccessTokenForRequest()).toBe("access-token");
    expect(store.getApplicantIdForRequest()).toBe("applicant-1");
    expect(storage.dump()).not.toContain("refresh-token");
    expect(storage.dump()).toContain("applicant-1");

    const reloaded = new BrowserSessionStore(storage);

    expect(reloaded.getAccessTokenForRequest()).toBe("access-token");
    expect(reloaded.getApplicantIdForRequest()).toBe("applicant-1");
  });

  it("does not persist phone-shaped mock applicant identifiers", async () => {
    const storage = new MemoryStorage();
    const store = new BrowserSessionStore(storage);

    await store.saveVerifiedSession({
      accessToken: "access-token",
      applicantId: "mock-applicant-opaque",
      expiresInSec: 3600,
    });

    expect(storage.dump()).not.toContain("91234567");
  });

  it("clears expired stored sessions", () => {
    const storage = new MemoryStorage();
    storage.setItem(
      "fides.mobileVerification.sessionPointer",
      JSON.stringify({
        applicantId: "applicant-1",
        accessToken: "expired-token",
        expiresAt: Date.now() - 1,
      }),
    );

    const store = new BrowserSessionStore(storage);

    expect(store.getAccessTokenForRequest()).toBeNull();
    expect(store.getApplicantIdForRequest()).toBeNull();
    expect(storage.dump()).not.toContain("expired-token");
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
