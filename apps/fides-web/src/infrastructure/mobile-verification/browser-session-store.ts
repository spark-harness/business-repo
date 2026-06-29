import type {
  SessionStore,
  VerifiedSession,
} from "@/application/mobile-verification/verified-session";

const SESSION_POINTER_KEY = "fides.mobileVerification.sessionPointer";

export class BrowserSessionStore implements SessionStore {
  private accessToken: string | null;
  private applicantId: string | null;

  constructor(private readonly storage: Storage | undefined = getSessionStorage()) {
    const session = this.loadStoredSession();
    this.accessToken = session?.accessToken ?? null;
    this.applicantId = session?.applicantId ?? null;
  }

  async saveVerifiedSession(session: VerifiedSession): Promise<void> {
    this.accessToken = session.accessToken;
    this.applicantId = session.applicantId;
    this.storage?.setItem(
      SESSION_POINTER_KEY,
      JSON.stringify({
        applicantId: session.applicantId,
        accessToken: session.accessToken,
        expiresAt: Date.now() + session.expiresInSec * 1000,
      }),
    );
  }

  async clearVerifiedSession(): Promise<void> {
    this.accessToken = null;
    this.applicantId = null;
    this.storage?.removeItem(SESSION_POINTER_KEY);
  }

  getAccessTokenForRequest(): string | null {
    return this.accessToken;
  }

  getApplicantIdForRequest(): string | null {
    return this.applicantId;
  }

  private loadStoredSession(): { accessToken: string; applicantId: string } | null {
    const raw = this.storage?.getItem(SESSION_POINTER_KEY);
    if (!raw) {
      return null;
    }
    try {
      const parsed = JSON.parse(raw) as Partial<{
        accessToken: string;
        applicantId: string;
        expiresAt: number;
      }>;
      if (
        typeof parsed.accessToken !== "string" ||
        parsed.accessToken.length === 0 ||
        typeof parsed.applicantId !== "string" ||
        parsed.applicantId.length === 0 ||
        typeof parsed.expiresAt !== "number" ||
        parsed.expiresAt <= Date.now()
      ) {
        this.storage?.removeItem(SESSION_POINTER_KEY);
        return null;
      }
      return {
        accessToken: parsed.accessToken,
        applicantId: parsed.applicantId,
      };
    } catch {
      this.storage?.removeItem(SESSION_POINTER_KEY);
      return null;
    }
  }
}

function getSessionStorage() {
  if (typeof window === "undefined") {
    return undefined;
  }

  return window.sessionStorage;
}
