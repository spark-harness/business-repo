import type {
  DraftDetail,
  DraftResult,
  DraftStore,
  LoanRequestGateway,
  LoanRequestInput,
  QuoteResult,
} from "@/application/loan-request/loan-request-gateway";

type ControllerResult<T> =
  | { ok: true; value: T }
  | { ok: false; error: LoanRequestError };

export type LoanRequestError = {
  field: "amount" | "purpose" | "form";
  message: string;
  traceId?: string;
};

export type LoanRequestContinueResult = DraftResult & {
  shouldNavigate: false;
};

export type LoanRequestQuoteView = QuoteResult;

export type LoanRequestDraftView = DraftDetail & {
  amount: string;
  term: number;
  purpose: string;
  quote: QuoteResult;
};

export type LoanRequestController = {
  load(): Promise<ControllerResult<null | LoanRequestDraftView>>;
  price(input: LoanRequestFormInput): Promise<ControllerResult<LoanRequestQuoteView>>;
  continue(input: {
    loan: LoanRequestFormInput | LoanRequestInput;
    quoteId: string;
  }): Promise<ControllerResult<LoanRequestContinueResult>>;
};

export type LoanRequestFormInput = {
  amount: string;
  term: number;
  purpose: string;
};

export function createLoanRequestController(
  gateway: LoanRequestGateway,
  store: DraftStore,
  createIdempotencyKey: () => string = defaultIdempotencyKey,
  currentApplicantId: () => string | null = () => null,
): LoanRequestController {
  return {
    async load() {
      try {
        const pointer = await loadUsableDraftPointer(store, currentApplicantId());
        if (!pointer) {
          return { ok: true, value: null };
        }
        return { ok: true, value: toDraftView(await gateway.getDraft(pointer.applicationId)) };
      } catch (error) {
        return { ok: false, error: toLoanRequestError(error) };
      }
    },

    async price(input) {
      try {
        const loan = normalizeLoanRequest(input);
        const value = await gateway.createQuote({
          loan,
          idempotencyKey: createIdempotencyKey(),
        });
        return { ok: true, value };
      } catch (error) {
        return { ok: false, error: toLoanRequestError(error) };
      }
    },

    async continue(input) {
      try {
        const loan = normalizeLoanRequest(input.loan);
        const applicantId = currentApplicantId();
        const pointer = await loadUsableDraftPointer(store, applicantId);
        const draft = pointer
          ? await gateway.patchDraft({
              applicationId: pointer.applicationId,
              loan,
              quoteId: input.quoteId,
              idempotencyKey: createIdempotencyKey(),
            })
          : await gateway.createDraft({
              loan,
              quoteId: input.quoteId,
              idempotencyKey: createIdempotencyKey(),
            });

        await store.saveDraftPointer({
          applicationId: draft.applicationId,
          applicantId: applicantId ?? pointer?.applicantId,
          currentStep: draft.currentStep,
        });

        return {
          ok: true,
          value: {
            ...draft,
            shouldNavigate: false,
          },
        };
      } catch (error) {
        return { ok: false, error: toLoanRequestError(error) };
      }
    },
  };
}

async function loadUsableDraftPointer(store: DraftStore, applicantId: string | null) {
  const pointer = await store.loadDraftPointer();
  if (!pointer) {
    return null;
  }
  if (pointer.applicantId && applicantId && pointer.applicantId === applicantId) {
    return pointer;
  }
  if (!pointer.applicantId && !applicantId) {
    return pointer;
  }
  await store.clearDraftPointer();
  return null;
}

function normalizeLoanRequest(input: LoanRequestFormInput | LoanRequestInput): LoanRequestInput {
  const amount = normalizeAmount(input.amount);
  if (!amount || amount < 5000 || amount > 500000) {
    throw { field: "amount", message: "Enter an amount between HKD $5,000 and $500,000." };
  }
  if (!input.purpose) {
    throw { field: "purpose", message: "Select what the loan is for." };
  }
  return {
    amount: amount.toFixed(2),
    term: input.term,
    purpose: normalizePurpose(input.purpose),
  };
}

function normalizeAmount(value: string): number {
  return Number(value.replace(/[^\d.]/g, ""));
}

function normalizePurpose(value: string): string {
  if (value === "debt") {
    return "debt_consolidation";
  }
  return value;
}

function toDraftView(detail: DraftDetail): LoanRequestDraftView {
  return {
    ...detail,
    amount: detail.loan.amount,
    term: detail.loan.term,
    purpose: detail.loan.purpose,
    quote: detail.acceptedQuote,
  };
}

function toLoanRequestError(error: unknown): LoanRequestError {
  if (error && typeof error === "object") {
    const record = error as Record<string, unknown>;
    return {
      field:
        record.field === "amount" || record.field === "purpose"
          ? record.field
          : "form",
      message:
        typeof record.message === "string" && record.message.length > 0
          ? record.message
          : "Request failed. Please try again.",
      traceId: typeof record.traceId === "string" ? record.traceId : undefined,
    };
  }
  return { field: "form", message: "Request failed. Please try again." };
}

function defaultIdempotencyKey(): string {
  return globalThis.crypto?.randomUUID?.() ?? `idem-${Date.now()}-${Math.random()}`;
}
