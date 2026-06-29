import { describe, expect, it, vi } from "vitest";

import { createLoanRequestController } from "./loan-request-controller";

describe("LoanRequestController", () => {
  it("prices loan terms using the real pricing gateway result", async () => {
    const gateway = createGateway();
    const controller = createLoanRequestController(gateway, createStore(), () => "idem-1");

    await expect(
      controller.price({ amount: "50,000", term: 9, purpose: "debt_consolidation" }),
    ).resolves.toMatchObject({
      ok: true,
      value: { quoteId: "quote_1", monthly: "5750.00", apr: "0.0385" },
    });

    expect(gateway.createQuote).toHaveBeenCalledWith({
      loan: { amount: "50000.00", term: 9, purpose: "debt_consolidation" },
      idempotencyKey: "idem-1",
    });
  });

  it("creates a draft on first Continue and returns no navigation instruction", async () => {
    const store = createStore();
    const controller = createLoanRequestController(createGateway(), store, () => "idem-1");

    await expect(
      controller.continue({
        loan: { amount: "50000.00", term: 9, purpose: "debt_consolidation" },
        quoteId: "quote_1",
      }),
    ).resolves.toMatchObject({
      ok: true,
      value: { applicationId: "app_1", shouldNavigate: false },
    });
    expect(store.saveDraftPointer).toHaveBeenCalledWith(
      expect.objectContaining({ applicationId: "app_1", currentStep: "loan_request" }),
    );
  });

  it("patches an existing draft on Continue", async () => {
    const gateway = createGateway();
    const store = createStore({ applicationId: "app_1", applicantId: "applicant_1", currentStep: "loan_request" });
    const controller = createLoanRequestController(gateway, store, () => "idem-1", () => "applicant_1");

    await controller.continue({
      loan: { amount: "50000.00", term: 9, purpose: "debt_consolidation" },
      quoteId: "quote_1",
    });

    expect(gateway.patchDraft).toHaveBeenCalledWith({
      applicationId: "app_1",
      loan: { amount: "50000.00", term: 9, purpose: "debt_consolidation" },
      quoteId: "quote_1",
      idempotencyKey: "idem-1",
    });
  });

  it("creates a new draft when the stored draft belongs to another applicant", async () => {
    const gateway = createGateway();
    const store = createStore({ applicationId: "app_old", applicantId: "applicant_old", currentStep: "loan_request" });
    const controller = createLoanRequestController(gateway, store, () => "idem-1", () => "applicant_new");

    await controller.continue({
      loan: { amount: "50000.00", term: 9, purpose: "debt_consolidation" },
      quoteId: "quote_1",
    });

    expect(gateway.patchDraft).not.toHaveBeenCalled();
    expect(gateway.createDraft).toHaveBeenCalledWith({
      loan: { amount: "50000.00", term: 9, purpose: "debt_consolidation" },
      quoteId: "quote_1",
      idempotencyKey: "idem-1",
    });
    expect(store.clearDraftPointer).toHaveBeenCalled();
    expect(store.saveDraftPointer).toHaveBeenCalledWith({
      applicationId: "app_1",
      applicantId: "applicant_new",
      currentStep: "loan_request",
    });
  });

  it("loads an existing draft as a refill view model", async () => {
    const controller = createLoanRequestController(
      createGateway(),
      createStore({ applicationId: "app_1", applicantId: "applicant_1", currentStep: "loan_request" }),
      () => "idem-1",
      () => "applicant_1",
    );

    await expect(controller.load()).resolves.toMatchObject({
      ok: true,
      value: {
        applicationId: "app_1",
        amount: "75000.00",
        term: 12,
        purpose: "education",
        quote: { quoteId: "quote_2", monthly: "6520.10" },
      },
    });
  });

  it("does not load a stored draft owned by another applicant", async () => {
    const gateway = createGateway();
    const store = createStore({ applicationId: "app_old", applicantId: "applicant_old", currentStep: "loan_request" });
    const controller = createLoanRequestController(gateway, store, () => "idem-1", () => "applicant_new");

    await expect(controller.load()).resolves.toEqual({ ok: true, value: null });

    expect(gateway.getDraft).not.toHaveBeenCalled();
    expect(store.clearDraftPointer).toHaveBeenCalled();
  });
});

function createGateway() {
  return {
    createQuote: vi.fn().mockResolvedValue({
      quoteId: "quote_1",
      monthly: "5750.00",
      apr: "0.0385",
      totalInterest: "1750.00",
      totalPayable: "51750.00",
      validUntil: "2026-06-28T10:00:00Z",
    }),
    createDraft: vi.fn().mockResolvedValue({
      applicationId: "app_1",
      status: "draft",
      currentStep: "loan_request",
    }),
    getDraft: vi.fn().mockResolvedValue({
      applicationId: "app_1",
      loan: { amount: "75000.00", term: 12, purpose: "education" },
      acceptedQuote: {
        quoteId: "quote_2",
        monthly: "6520.10",
        apr: "0.0520",
        totalInterest: "3241.20",
        totalPayable: "78241.20",
        validUntil: "2026-06-28T10:00:00Z",
      },
      status: "draft",
      currentStep: "loan_request",
    }),
    patchDraft: vi.fn().mockResolvedValue({
      applicationId: "app_1",
      status: "draft",
      currentStep: "loan_request",
    }),
  };
}

function createStore(pointer: unknown = null) {
  return {
    loadDraftPointer: vi.fn().mockResolvedValue(pointer),
    saveDraftPointer: vi.fn().mockResolvedValue(undefined),
    clearDraftPointer: vi.fn().mockResolvedValue(undefined),
  };
}
