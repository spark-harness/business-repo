import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { LoanRequestController } from "@/adapters/loan-request/loan-request-controller";
import { LoanRequestScreen } from "./loan-request-screen";

describe("LoanRequestScreen", () => {
  afterEach(() => {
    cleanup();
  });

  it("renders the code.html loan request structure", async () => {
    render(<LoanRequestScreen controller={createController()} />);

    expect(screen.getByRole("heading", { name: "How much do you need?" })).toBeTruthy();
    expect(screen.getByRole("progressbar", { name: "Application progress" })).toHaveProperty(
      "ariaValueNow",
      "2",
    );
    expect(screen.getByLabelText("Loan Amount (HKD)")).toBeTruthy();
    expect(screen.getByLabelText("Loan Term (Months)")).toBeTruthy();
    expect(screen.getByLabelText("Loan Purpose")).toBeTruthy();
    expect(screen.getByText("Estimated Summary")).toBeTruthy();
  });

  it("shows pricing from the BFF quote response", async () => {
    const controller = createController();
    render(<LoanRequestScreen controller={controller} />);

    await userEvent.selectOptions(screen.getByLabelText("Loan Purpose"), "debt_consolidation");

    await waitFor(() => {
      expect(screen.getByText("HKD $5,750.00")).toBeTruthy();
    });
    expect(controller.price).toHaveBeenCalled();
  });

  it("continues by saving a draft and stays on the loan request screen", async () => {
    const controller = createController();
    render(<LoanRequestScreen controller={controller} />);

    await userEvent.selectOptions(screen.getByLabelText("Loan Purpose"), "debt_consolidation");
    await screen.findByText("HKD $5,750.00");
    await userEvent.click(screen.getByRole("button", { name: /Continue/ }));

    await waitFor(() => {
      expect(controller.continue).toHaveBeenCalled();
    });
    expect(screen.getByRole("heading", { name: "How much do you need?" })).toBeTruthy();
    expect(screen.queryByRole("status")).toBeNull();
  });

  it("refills the same draft from the BFF draft detail", async () => {
    const controller = createController();
    controller.load = vi.fn().mockResolvedValue({
      ok: true,
      value: {
        applicationId: "app_1",
        amount: "75000.00",
        term: 12,
        purpose: "education",
        quote: {
          quoteId: "quote_2",
          monthly: "6520.10",
          apr: "0.0520",
          totalInterest: "3241.20",
          totalPayable: "78241.20",
          validUntil: "2026-06-28T10:00:00Z",
        },
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
      },
    });

    render(<LoanRequestScreen controller={controller} />);

    await waitFor(() => {
      expect(screen.getByLabelText("Loan Amount (HKD)")).toHaveProperty("value", "75,000");
    });
    expect(screen.getByLabelText("Loan Term (Months)")).toHaveProperty("value", "12");
    expect(screen.getByLabelText("Loan Purpose")).toHaveProperty("value", "education");
    expect(screen.getByText("HKD $6,520.10")).toBeTruthy();
  });
});

function createController(): LoanRequestController {
  return {
    load: vi.fn().mockResolvedValue({ ok: true, value: null }),
    price: vi.fn().mockResolvedValue({
      ok: true,
      value: {
        quoteId: "quote_1",
        monthly: "5750.00",
        apr: "0.0385",
        totalInterest: "1750.00",
        totalPayable: "51750.00",
        validUntil: "2026-06-28T10:00:00Z",
      },
    }),
    continue: vi.fn().mockResolvedValue({
      ok: true,
      value: { applicationId: "app_1", status: "draft", currentStep: "loan_request", shouldNavigate: false },
    }),
  };
}
