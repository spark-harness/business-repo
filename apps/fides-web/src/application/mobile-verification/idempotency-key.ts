type UserIntent = "send-otp" | "verify-otp";

export class UserIntentIdempotencyKeys {
  private readonly keys = new Map<UserIntent, string>();

  constructor(private readonly generateKey: () => string = createIdempotencyKey) {}

  current(intent: UserIntent): string {
    const existing = this.keys.get(intent);
    if (existing) {
      return existing;
    }

    const next = this.generateKey();
    this.keys.set(intent, next);
    return next;
  }

  rotate(intent: UserIntent): string {
    const next = this.generateKey();
    this.keys.set(intent, next);
    return next;
  }
}

function createIdempotencyKey(): string {
  return globalThis.crypto?.randomUUID?.() ?? `idem-${Date.now()}-${Math.random()}`;
}
