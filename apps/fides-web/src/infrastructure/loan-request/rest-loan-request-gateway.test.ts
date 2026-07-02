import { describe, expect, it, vi } from "vitest";

import { RestLoanRequestGateway } from "./rest-loan-request-gateway";

describe("RestLoanRequestGateway", () => {
  it("posts pricing quotes to the BFF with auth and idempotency headers only", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      jsonResponse({
        quoteId: "quote_1",
        monthly: "5750.00",
        apr: "0.0385",
        totalInterest: "1750.00",
        totalPayable: "51750.00",
        validUntil: "2026-06-28T10:00:00Z",
      }),
    );
    const gateway = new RestLoanRequestGateway("/api/v1", () => "access-token", fetcher);

    await expect(
      gateway.createQuote({
        loan: { amount: "50000.00", term: 9, purpose: "debt_consolidation" },
        idempotencyKey: "price-key",
      }),
    ).resolves.toMatchObject({ quoteId: "quote_1", monthly: "5750.00" });

    expect(fetcher).toHaveBeenCalledWith("/api/v1/pricing/quotes", {
      method: "POST",
      headers: {
        Authorization: "Bearer access-token",
        "Content-Type": "application/json",
        "Idempotency-Key": "price-key",
      },
      body: JSON.stringify({
        productCode: "PIL",
        amount: "50000.00",
        term: 9,
        purpose: "debt_consolidation",
      }),
      signal: expect.any(AbortSignal),
    });
  });

  it("creates and patches loan application drafts through the BFF only", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse({ applicationId: "app_1", status: "draft", currentStep: "loan_request" }))
      .mockResolvedValueOnce(jsonResponse({ applicationId: "app_1", status: "draft", currentStep: "loan_request" }));
    const gateway = new RestLoanRequestGateway("/api/v1", () => "access-token", fetcher);

    await gateway.createDraft({
      loan: { amount: "50000.00", term: 9, purpose: "debt_consolidation" },
      quoteId: "quote_1",
      idempotencyKey: "create-key",
    });
    await gateway.patchDraft({
      applicationId: "app_1",
      loan: { amount: "50000.00", term: 9, purpose: "debt_consolidation" },
      quoteId: "quote_1",
      idempotencyKey: "patch-key",
    });

    expect(fetcher).toHaveBeenNthCalledWith(
      1,
      "/api/v1/loan-applications",
      expect.objectContaining({ method: "POST" }),
    );
    expect(fetcher).toHaveBeenNthCalledWith(
      2,
      "/api/v1/loan-applications/app_1",
      expect.objectContaining({ method: "PATCH" }),
    );
  });

  it("loads draft details from the BFF for same-draft refill", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      jsonResponse({
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
    );
    const gateway = new RestLoanRequestGateway("/api/v1", () => "access-token", fetcher);

    await expect(gateway.getDraft("app_1")).resolves.toMatchObject({
      applicationId: "app_1",
      loan: { amount: "75000.00", term: 12, purpose: "education" },
      acceptedQuote: { quoteId: "quote_2", monthly: "6520.10" },
    });

    expect(fetcher).toHaveBeenCalledWith(
      "/api/v1/loan-applications/app_1",
      expect.objectContaining({
        method: "GET",
        headers: expect.objectContaining({ Authorization: "Bearer access-token" }),
      }),
    );
  });
});

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(body === undefined ? undefined : JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
