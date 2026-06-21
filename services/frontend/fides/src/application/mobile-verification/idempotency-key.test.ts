import { describe, expect, it } from "vitest";

import { UserIntentIdempotencyKeys } from "./idempotency-key";

describe("UserIntentIdempotencyKeys", () => {
  it("reuses the same key while retrying one user intent", () => {
    const keys = new UserIntentIdempotencyKeys(() => "key-1");

    expect(keys.current("send-otp")).toBe("key-1");
    expect(keys.current("send-otp")).toBe("key-1");
  });

  it("generates a new key for a new user intent", () => {
    const generated = ["key-1", "key-2"];
    const keys = new UserIntentIdempotencyKeys(() => generated.shift() ?? "unexpected");

    expect(keys.current("verify-otp")).toBe("key-1");
    keys.rotate("verify-otp");
    expect(keys.current("verify-otp")).toBe("key-2");
  });
});
