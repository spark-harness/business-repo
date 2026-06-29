"use client";

import { useEffect, useState } from "react";

import type {
  LoanRequestController,
  LoanRequestFormInput,
  LoanRequestQuoteView,
} from "@/adapters/loan-request/loan-request-controller";

type LoanRequestScreenProps = {
  controller: LoanRequestController;
  onContinue?: () => void;
};

type FormError = {
  field: "amount" | "purpose" | "form";
  message: string;
};

const terms = [3, 6, 9, 12, 24];
const purposeOptions = [
  { value: "home", label: "Home Renovation" },
  { value: "debt_consolidation", label: "Debt Consolidation" },
  { value: "education", label: "Education" },
  { value: "business", label: "Business Expansion" },
  { value: "other", label: "Other" },
];

export function LoanRequestScreen({ controller, onContinue }: LoanRequestScreenProps) {
  const [amount, setAmount] = useState("50,000");
  const [term, setTerm] = useState(9);
  const [purpose, setPurpose] = useState("");
  const [quoteState, setQuoteState] = useState<{
    key: string;
    quote: LoanRequestQuoteView;
  } | null>(null);
  const [isSaving, setIsSaving] = useState(false);
  const [error, setError] = useState<FormError | null>(null);

  const loan: LoanRequestFormInput = { amount, term, purpose };
  const pricingKey = `${amount}|${term}|${purpose}`;
  const quote = quoteState?.key === pricingKey ? quoteState.quote : null;

  useEffect(() => {
    let cancelled = false;
    void controller.load().then((result) => {
      if (cancelled || !result.ok || !result.value) {
        return;
      }
      setAmount(formatLoadedAmount(result.value.amount));
      setTerm(result.value.term);
      setPurpose(result.value.purpose);
      setQuoteState({
        key: `${formatLoadedAmount(result.value.amount)}|${result.value.term}|${result.value.purpose}`,
        quote: result.value.quote,
      });
    });
    return () => {
      cancelled = true;
    };
  }, [controller]);

  useEffect(() => {
    if (!purpose || !validAmount(amount)) {
      return;
    }

    let cancelled = false;
    const requestKey = `${amount}|${term}|${purpose}`;
    const timer = window.setTimeout(() => {
      void controller.price({ amount, term, purpose }).then((result) => {
        if (cancelled) {
          return;
        }
        if (!result.ok) {
          setError(result.error);
          setQuoteState(null);
          return;
        }
        setError(null);
        setQuoteState({ key: requestKey, quote: result.value });
      });
    }, 150);

    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [amount, controller, purpose, term]);

  async function continueDraft() {
    setError(null);
    const validationError = validate(loan);
    if (validationError) {
      setError(validationError);
      focusField(validationError.field);
      return;
    }
    if (!quote) {
      setError({ field: "form", message: "Please wait for the latest quote." });
      return;
    }

    setIsSaving(true);
    try {
      const result = await controller.continue({
        loan,
        quoteId: quote.quoteId,
      });
      if (!result.ok) {
        setError(result.error);
        return;
      }
      onContinue?.();
    } finally {
      setIsSaving(false);
    }
  }

  return (
    <div className="min-h-[max(884px,100dvh)] overflow-x-hidden bg-background text-on-background selection:bg-primary-container selection:text-on-primary-container">
      <header className="fixed left-0 right-0 top-0 z-50 bg-surface/80 backdrop-blur-md">
        <div className="mx-auto flex h-16 w-full max-w-2xl items-center justify-between px-gutter">
          <button
            aria-label="Back"
            className="-ml-2 flex h-11 w-11 items-center justify-center rounded-full text-on-surface-variant transition-colors hover:bg-surface-container-high active:scale-95 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40"
            type="button"
          >
            <ArrowBackIcon className="h-6 w-6" />
          </button>
          <a
            href="#"
            aria-label="Lendora"
            className="flex items-center gap-2 rounded-lg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40"
          >
            <span
              aria-hidden="true"
              className="flex h-7 w-7 items-center justify-center rounded-lg bg-primary text-[15px] font-semibold leading-none text-on-primary"
            >
              L
            </span>
            <span className="font-headline-md text-[19px] font-semibold tracking-tight text-on-surface">
              Lendora
            </span>
          </a>
          <button
            aria-label="More options"
            className="-mr-2 flex h-11 w-11 items-center justify-center rounded-full text-on-surface-variant transition-colors hover:bg-surface-container-high active:scale-95 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40"
            type="button"
          >
            <MoreVertIcon className="h-6 w-6" />
          </button>
        </div>
        <div
          className="h-1 w-full bg-surface-container-high"
          role="progressbar"
          aria-label="Application progress"
          aria-valuemin={1}
          aria-valuemax={7}
          aria-valuenow={2}
          aria-valuetext="Step 2 of 7"
        >
          <div className="h-full w-[28.57%] rounded-r-full bg-primary transition-[width] duration-300 ease-out" />
        </div>
      </header>

      <main className="mx-auto flex w-full max-w-2xl flex-col gap-stack-lg px-container-padding-mobile pb-36 pt-24">
        <section className="flex flex-col gap-2">
          <div className="flex items-center gap-3">
            <span
              className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-primary-container/30 text-primary"
              aria-hidden="true"
            >
              <PaymentsIcon className="h-[22px] w-[22px]" />
            </span>
            <h1 className="font-headline-lg-mobile text-headline-lg-mobile text-on-surface md:font-headline-lg md:text-headline-lg">
              How much do you need?
            </h1>
          </div>
          <p className="font-body-md text-body-md text-on-surface-variant">
            Customize your loan amount and terms below.
          </p>
        </section>

        <form
          id="loan-form"
          noValidate
          className="flex flex-col gap-stack-md"
          onSubmit={(event) => {
            event.preventDefault();
            void continueDraft();
          }}
        >
          <div className="flex flex-col gap-2">
            <label
              className="block font-label-md text-label-md text-on-surface-variant"
              htmlFor="loan-amount-input"
            >
              Loan Amount (HKD)
            </label>
            <div className="relative">
              <div className="pointer-events-none absolute inset-y-0 left-0 flex items-center pl-4 font-headline-md text-headline-md text-primary">
                $
              </div>
              <input
                id="loan-amount-input"
                aria-invalid={error?.field === "amount"}
                aria-describedby="amount-hint amount-error"
                className="field-control h-14 w-full rounded-lg border border-outline-variant bg-surface-container-lowest pl-11 pr-4 font-headline-md text-headline-md text-on-surface placeholder:text-outline focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20 aria-[invalid=true]:border-error aria-[invalid=true]:focus:ring-error/20"
                inputMode="numeric"
                placeholder="50,000"
                type="text"
                value={amount}
                onBlur={() => setError(validate({ ...loan, amount }))}
                onChange={(event) => {
                  setAmount(formatAmount(event.target.value));
                  if (error?.field === "amount") {
                    setError(null);
                  }
                }}
              />
            </div>
            <p id="amount-hint" className="font-label-sm text-label-sm text-on-surface-variant">
              Borrow between HKD $5,000 and $500,000.
            </p>
            <p
              id="amount-error"
              role="alert"
              className={error?.field === "amount" ? "font-label-md text-label-md text-error" : "hidden"}
            >
              {error?.field === "amount" ? error.message : ""}
            </p>
          </div>

          <div className="flex flex-col gap-2">
            <label className="block font-label-md text-label-md text-on-surface-variant" htmlFor="loan-term">
              Loan Term (Months)
            </label>
            <div className="relative">
              <select
                id="loan-term"
                className="field-control h-14 w-full cursor-pointer appearance-none rounded-lg border border-outline-variant bg-surface-container-lowest pl-4 pr-12 font-body-md text-body-md text-on-surface focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20"
                value={term}
                onChange={(event) => setTerm(Number(event.target.value))}
              >
                {terms.map((item) => (
                  <option key={item} value={item}>
                    {item} Months
                  </option>
                ))}
              </select>
              <ExpandMoreIcon className="pointer-events-none absolute right-3 top-1/2 h-5 w-5 -translate-y-1/2 text-on-surface-variant" />
            </div>
          </div>

          <div className="flex flex-col gap-2">
            <label className="block font-label-md text-label-md text-on-surface-variant" htmlFor="loan-purpose">
              Loan Purpose
            </label>
            <div className="relative">
              <select
                id="loan-purpose"
                aria-invalid={error?.field === "purpose"}
                aria-describedby="purpose-error"
                className="field-control h-14 w-full cursor-pointer appearance-none rounded-lg border border-outline-variant bg-surface-container-lowest pl-4 pr-12 font-body-md text-body-md text-on-surface focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20 aria-[invalid=true]:border-error"
                required
                value={purpose}
                onChange={(event) => {
                  setPurpose(event.target.value);
                  if (error?.field === "purpose") {
                    setError(null);
                  }
                }}
              >
                <option disabled hidden value="">
                  Select a purpose...
                </option>
                {purposeOptions.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
              <ExpandMoreIcon className="pointer-events-none absolute right-3 top-1/2 h-5 w-5 -translate-y-1/2 text-on-surface-variant" />
            </div>
            <p
              id="purpose-error"
              role="alert"
              className={error?.field === "purpose" ? "font-label-md text-label-md text-error" : "hidden"}
            >
              {error?.field === "purpose" ? error.message : ""}
            </p>
          </div>

          {error?.field === "form" ? (
            <p className="font-label-md text-label-md text-error" role="alert">
              {error.message}
            </p>
          ) : null}
        </form>

        <section
          className="flex flex-col gap-4 rounded-lg border border-outline-variant bg-surface-container-lowest p-stack-md"
          aria-live="polite"
        >
          <h2 className="font-label-md text-label-md font-semibold uppercase tracking-wider text-primary">
            Estimated Summary
          </h2>
          <div className="flex flex-col gap-3">
            <SummaryRow label="Monthly Repayment" value={moneyValue(quote?.monthly)} />
            <SummaryRow label="Representative APR" value={aprValue(quote?.apr)} />
            <SummaryRow label="Total Interest" value={moneyValue(quote?.totalInterest)} last />
          </div>
          <p className="font-label-sm text-label-sm text-on-surface-variant">
            These figures are estimates. Final terms will be determined after credit assessment.
          </p>
        </section>
      </main>

      <nav className="fixed bottom-0 left-0 right-0 z-50 border-t border-outline-variant/40 bg-surface/90 backdrop-blur-md">
        <div className="mx-auto max-w-2xl px-gutter py-4">
          <button
            form="loan-form"
            type="submit"
            disabled={isSaving}
            className="flex h-14 w-full items-center justify-center gap-2 rounded-lg bg-primary font-semibold text-on-primary transition-all duration-150 hover:brightness-110 active:scale-[0.98] disabled:cursor-not-allowed disabled:opacity-70 disabled:active:scale-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40 focus-visible:ring-offset-2 focus-visible:ring-offset-surface"
          >
            {isSaving ? (
              <>
                <SpinnerIcon className="h-5 w-5 animate-spin" />
                <span className="font-label-md text-label-md">Saving...</span>
              </>
            ) : (
              <>
                <span className="font-label-md text-label-md">Continue</span>
                <ArrowForwardIcon className="h-5 w-5" />
              </>
            )}
          </button>
        </div>
      </nav>
    </div>
  );
}

function SummaryRow({ label, value, last = false }: { label: string; value: string; last?: boolean }) {
  return (
    <div className={`flex items-center justify-between ${last ? "" : "border-b border-outline-variant/40 pb-3"}`}>
      <span className="font-body-md text-body-md text-on-surface-variant">{label}</span>
      <span className="font-body-lg text-body-lg font-medium tabular-nums text-on-surface">{value}</span>
    </div>
  );
}

function validate(input: LoanRequestFormInput): FormError | null {
  if (!validAmount(input.amount)) {
    return { field: "amount", message: "Enter an amount between HKD $5,000 and $500,000." };
  }
  if (!input.purpose) {
    return { field: "purpose", message: "Select what the loan is for." };
  }
  return null;
}

function focusField(field: FormError["field"]) {
  if (field === "amount") {
    document.getElementById("loan-amount-input")?.focus();
  }
  if (field === "purpose") {
    document.getElementById("loan-purpose")?.focus();
  }
}

function validAmount(value: string): boolean {
  const amount = Number(value.replace(/\D/g, ""));
  return amount >= 5000 && amount <= 500000;
}

function formatAmount(value: string): string {
  const raw = value.replace(/\D/g, "").slice(0, 6);
  return raw ? Number(raw).toLocaleString("en-US") : "";
}

function formatLoadedAmount(value: string): string {
  const amount = Number(value);
  if (!Number.isFinite(amount)) {
    return formatAmount(value);
  }
  return Math.round(amount).toLocaleString("en-US");
}

function moneyValue(value: string | undefined): string {
  if (!value) {
    return "-";
  }
  const amount = Number(value);
  if (!Number.isFinite(amount)) {
    return "-";
  }
  return `HKD $${amount.toLocaleString("en-US", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`;
}

function aprValue(value: string | undefined): string {
  if (!value) {
    return "-";
  }
  const apr = Number(value);
  if (!Number.isFinite(apr)) {
    return "-";
  }
  return `${(apr * 100).toFixed(2)}%`;
}

function ArrowBackIcon({ className }: { className: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path
        d="M19 12H5M12 19l-7-7 7-7"
        stroke="currentColor"
        strokeLinecap="round"
        strokeLinejoin="round"
        strokeWidth="2.2"
      />
    </svg>
  );
}

function MoreVertIcon({ className }: { className: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <circle cx="12" cy="5" r="1.8" />
      <circle cx="12" cy="12" r="1.8" />
      <circle cx="12" cy="19" r="1.8" />
    </svg>
  );
}

function PaymentsIcon({ className }: { className: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <rect x="4" y="6" width="16" height="12" rx="2" stroke="currentColor" strokeWidth="2" />
      <path d="M4 10h16M8 14h4" stroke="currentColor" strokeLinecap="round" strokeWidth="2" />
    </svg>
  );
}

function ExpandMoreIcon({ className }: { className: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path
        d="m6 9 6 6 6-6"
        stroke="currentColor"
        strokeLinecap="round"
        strokeLinejoin="round"
        strokeWidth="2.2"
      />
    </svg>
  );
}

function ArrowForwardIcon({ className }: { className: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path
        d="M5 12h14M12 5l7 7-7 7"
        stroke="currentColor"
        strokeLinecap="round"
        strokeLinejoin="round"
        strokeWidth="2.2"
      />
    </svg>
  );
}

function SpinnerIcon({ className }: { className: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="3" />
      <path className="opacity-90" d="M12 2a10 10 0 0 1 10 10" stroke="currentColor" strokeLinecap="round" strokeWidth="3" />
    </svg>
  );
}
