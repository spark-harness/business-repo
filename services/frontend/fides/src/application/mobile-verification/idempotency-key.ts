type UserIntent = "send-otp" | "verify-otp";

export class UserIntentIdempotencyKeys {
  private readonly keys = new Map<UserIntent, string>();

  constructor(private readonly generateKey: () => string = crypto.randomUUID) {}

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
