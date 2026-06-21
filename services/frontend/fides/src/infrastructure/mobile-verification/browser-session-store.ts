import type {
  SessionStore,
  VerifiedSession,
} from "@/application/mobile-verification/verified-session";

const SESSION_POINTER_KEY = "fides.mobileVerification.sessionPointer";

export class BrowserSessionStore implements SessionStore {
  private accessToken: string | null = null;

  constructor(private readonly storage: Storage | undefined = getSessionStorage()) {}

  async saveVerifiedSession(session: VerifiedSession): Promise<void> {
    this.accessToken = session.accessToken;
    this.storage?.setItem(
      SESSION_POINTER_KEY,
      JSON.stringify({
        applicantId: session.applicantId,
        expiresAt: Date.now() + session.expiresInSec * 1000,
      }),
    );
  }

  async clearVerifiedSession(): Promise<void> {
    this.accessToken = null;
    this.storage?.removeItem(SESSION_POINTER_KEY);
  }

  getAccessTokenForRequest(): string | null {
    return this.accessToken;
  }
}

function getSessionStorage() {
  if (typeof window === "undefined") {
    return undefined;
  }

  return window.sessionStorage;
}
