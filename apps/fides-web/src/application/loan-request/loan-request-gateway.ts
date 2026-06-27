export type LoanRequestInput = {
  amount: string;
  term: number;
  purpose: string;
};

export type QuoteResult = {
  quoteId: string;
  monthly: string;
  apr: string;
  totalInterest: string;
  totalPayable: string;
  validUntil: string;
};

export type DraftResult = {
  applicationId: string;
  status: string;
  currentStep: string;
};

export type DraftDetail = DraftResult & {
  loan: LoanRequestInput;
  acceptedQuote: QuoteResult;
};

export type DraftPointer = {
  applicationId: string;
  applicantId?: string;
  currentStep: string;
};

export interface LoanRequestGateway {
  createQuote(command: {
    loan: LoanRequestInput;
    idempotencyKey: string;
  }): Promise<QuoteResult>;

  createDraft(command: {
    loan: LoanRequestInput;
    quoteId: string;
    idempotencyKey: string;
  }): Promise<DraftResult>;

  getDraft(applicationId: string): Promise<DraftDetail>;

  patchDraft(command: {
    applicationId: string;
    loan: LoanRequestInput;
    quoteId: string;
    idempotencyKey: string;
  }): Promise<DraftResult>;
}

export interface DraftStore {
  loadDraftPointer(): Promise<DraftPointer | null>;
  saveDraftPointer(pointer: DraftPointer & { accessToken?: string }): Promise<void>;
  clearDraftPointer(): Promise<void>;
}
