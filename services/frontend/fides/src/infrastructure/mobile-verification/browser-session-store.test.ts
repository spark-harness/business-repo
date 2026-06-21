import { describe, expect, it } from "vitest";

import { BrowserSessionStore } from "./browser-session-store";

describe("BrowserSessionStore", () => {
  it("keeps access token in memory and stores only non-sensitive session pointer", async () => {
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
    expect(storage.dump()).not.toContain("access-token");
    expect(storage.dump()).not.toContain("refresh-token");
    expect(storage.dump()).toContain("applicant-1");
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
