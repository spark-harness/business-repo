import type { DraftPointer, DraftStore } from "@/application/loan-request/loan-request-gateway";

const DRAFT_POINTER_KEY = "fides.loanRequest.draftPointer";

export class BrowserDraftStore implements DraftStore {
  constructor(private readonly storage: Storage | undefined = getSessionStorage()) {}

  async loadDraftPointer(): Promise<DraftPointer | null> {
    const raw = this.storage?.getItem(DRAFT_POINTER_KEY);
    if (!raw) {
      return null;
    }
    try {
      const parsed = JSON.parse(raw) as Partial<DraftPointer>;
      if (typeof parsed.applicationId !== "string" || typeof parsed.currentStep !== "string") {
        return null;
      }
      return {
        applicationId: parsed.applicationId,
        applicantId: typeof parsed.applicantId === "string" ? parsed.applicantId : undefined,
        currentStep: parsed.currentStep,
      };
    } catch {
      return null;
    }
  }

  async saveDraftPointer(pointer: DraftPointer & { accessToken?: string }): Promise<void> {
    this.storage?.setItem(
      DRAFT_POINTER_KEY,
      JSON.stringify({
        applicationId: pointer.applicationId,
        applicantId: pointer.applicantId,
        currentStep: pointer.currentStep,
      }),
    );
  }

  async clearDraftPointer(): Promise<void> {
    this.storage?.removeItem(DRAFT_POINTER_KEY);
  }
}

function getSessionStorage() {
  if (typeof window === "undefined") {
    return undefined;
  }

  return window.sessionStorage;
}
