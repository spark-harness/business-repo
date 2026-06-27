"use client";

import { useEffect, useRef, useState } from "react";

import type {
  MobileVerificationController,
  MobileVerificationSendResult,
  MobileVerificationVerifiedResult,
} from "@/adapters/mobile-verification/mobile-verification-controller";
import { createDefaultMobileVerificationController } from "@/api/mobile-verification/create-mobile-verification-controller";
import type { PublicRuntimeConfig } from "@/api/runtime-config/public-runtime-config";

type MobileVerificationScreenProps = {
  controller?: MobileVerificationController;
  runtimeConfig?: PublicRuntimeConfig;
  onVerified?: (result: MobileVerificationVerifiedResult) => void;
};

type FormError = {
  field: "phone" | "otp" | "form";
  message: string;
};

const fallbackRuntimeConfig: PublicRuntimeConfig = {
  otpAdapter: "mock",
  bffBaseUrl: "/api/v1",
  browserTracing: { headers: {} },
};

export function MobileVerificationScreen({
  controller,
  runtimeConfig = fallbackRuntimeConfig,
  onVerified,
}: MobileVerificationScreenProps) {
  const resolvedController = controller ?? createDefaultMobileVerificationController(runtimeConfig);
  const [countryCode, setCountryCode] = useState("+852");
  const [phone, setPhone] = useState("");
  const [otpDigits, setOtpDigits] = useState(["", "", "", "", "", ""]);
  const [challenge, setChallenge] = useState<MobileVerificationSendResult | null>(null);
  const [cooldown, setCooldown] = useState(0);
  const [isSending, setIsSending] = useState(false);
  const [isVerifying, setIsVerifying] = useState(false);
  const [error, setError] = useState<FormError | null>(null);
  const otpRefs = useRef<Array<HTMLInputElement | null>>([]);

  const canSend = !isSending && cooldown === 0;
  const otpCode = otpDigits.join("");

  useEffect(() => {
    if (cooldown <= 0) {
      return;
    }
    const timer = window.setInterval(() => {
      setCooldown((current) => Math.max(0, current - 1));
    }, 1000);
    return () => window.clearInterval(timer);
  }, [cooldown]);

  async function sendOtp() {
    setError(null);
    setIsSending(true);
    try {
      const result = await resolvedController.sendOtp({ countryCode, phone });
      if (!result.ok) {
        setError(result.error);
        if (result.error.retryAfterSec) {
          setCooldown(result.error.retryAfterSec);
        }
        return;
      }
      setChallenge(result.value);
      setCooldown(result.value.resendAfterSec);
      setOtpDigits(["", "", "", "", "", ""]);
      window.setTimeout(() => otpRefs.current[0]?.focus(), 0);
    } finally {
      setIsSending(false);
    }
  }

  async function verifyOtp() {
    setError(null);
    if (!challenge) {
      await sendOtp();
      return;
    }

    setIsVerifying(true);
    try {
      const result = await resolvedController.verifyOtp({
        challengeId: challenge.challengeId,
        code: otpCode,
      });
      if (!result.ok) {
        setError(result.error);
        if (result.error.retryAfterSec) {
          setCooldown(result.error.retryAfterSec);
        }
        if (result.error.field === "otp") {
          otpRefs.current[0]?.focus();
        }
        return;
      }
      onVerified?.(result.value);
    } finally {
      setIsVerifying(false);
    }
  }

  function setOtpDigit(index: number, value: string) {
    const digit = value.replace(/\D/g, "").slice(-1);
    setOtpDigits((current) => current.map((item, itemIndex) => (itemIndex === index ? digit : item)));
    if (digit && index < otpDigits.length - 1) {
      otpRefs.current[index + 1]?.focus();
    }
  }

  function pasteOtp(value: string) {
    const digits = value.replace(/\D/g, "").slice(0, 6).split("");
    if (digits.length === 0) {
      return;
    }
    setOtpDigits((current) => current.map((_, index) => digits[index] ?? ""));
    otpRefs.current[Math.min(digits.length, 5)]?.focus();
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
          aria-valuenow={1}
          aria-valuetext="Step 1 of 7"
        >
          <div className="h-full w-[14.29%] rounded-r-full bg-primary" />
        </div>
      </header>

      <main className="mx-auto flex w-full max-w-2xl flex-col gap-stack-lg px-container-padding-mobile pb-36 pt-24">
        <section className="flex flex-col gap-2">
          <div className="flex items-center gap-3">
            <span
              className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-primary-container/30 text-primary"
              aria-hidden="true"
            >
              <SmsIcon className="h-[22px] w-[22px]" />
            </span>
            <h1 className="font-headline-lg-mobile text-headline-lg-mobile text-on-surface md:font-headline-lg md:text-headline-lg">
              Verify your mobile
            </h1>
          </div>
          <p className="font-body-md text-body-md text-on-surface-variant">
            We&apos;ll text a one-time code to confirm it&apos;s really you.
          </p>
        </section>

        <form
          id="verify-form"
          noValidate
          className="flex flex-col gap-stack-md"
          onSubmit={(event) => {
            event.preventDefault();
            void verifyOtp();
          }}
        >
          <div className="flex flex-col">
            <label className="mb-2 block font-label-md text-label-md text-on-surface-variant" htmlFor="phone-number">
              Mobile Number
            </label>
            <div className="relative flex items-center">
              <div className="absolute inset-y-0 left-0 z-10 flex items-center pl-3">
                <select
                  aria-label="Country code"
                  className="h-full cursor-pointer appearance-none border-transparent bg-transparent py-0 pl-2 pr-7 font-body-md text-body-md text-on-surface focus:ring-0"
                  value={countryCode}
                  onChange={(event) => setCountryCode(event.target.value)}
                >
                  <option value="+852">+852</option>
                  <option value="+86">+86</option>
                  <option value="+44">+44</option>
                </select>
                <ExpandMoreIcon className="pointer-events-none absolute right-1 h-5 w-5 text-on-surface-variant" />
                <div className="absolute right-0 top-1/2 h-6 w-px -translate-y-1/2 bg-outline-variant" />
              </div>
              <input
                id="phone-number"
                aria-invalid={error?.field === "phone"}
                aria-describedby="phone-error"
                className="field-control h-14 w-full rounded-lg border border-outline-variant bg-surface-container-lowest pl-28 pr-24 font-body-md text-body-md text-on-surface placeholder:text-outline focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20 aria-[invalid=true]:border-error aria-[invalid=true]:focus:ring-error/20"
                inputMode="numeric"
                autoComplete="tel-national"
                maxLength={9}
                placeholder="9123 4567"
                required
                type="tel"
                value={phone}
                onChange={(event) => setPhone(formatPhone(event.target.value))}
              />
              <button
                className="absolute right-2 top-1/2 -translate-y-1/2 rounded-lg bg-primary/10 px-4 py-2 font-label-md text-label-md text-primary transition-colors hover:bg-primary/15 disabled:cursor-default disabled:text-on-surface-variant focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/30"
                disabled={!canSend}
                type="button"
                onClick={() => void sendOtp()}
              >
                {isSending ? "Sending" : cooldown > 0 ? "Sent" : "Send"}
              </button>
            </div>
            <p
              id="phone-error"
              role="alert"
              className={error?.field === "phone" ? "mt-2 font-label-md text-label-md text-error" : "hidden"}
            >
              {error?.field === "phone" ? error.message : ""}
            </p>
          </div>

          {challenge ? (
            <div className="flex flex-col gap-stack-sm animate-[fadeIn_0.3s_ease-out_forwards]">
              <div className="flex items-center justify-between">
                <label
                  className="block font-label-md text-label-md text-on-surface-variant"
                  id="otp-label"
                >
                  Enter 6-digit code
                </label>
                <button
                  type="button"
                  disabled={cooldown > 0}
                  className="rounded font-label-sm text-label-sm text-on-surface-variant disabled:cursor-default enabled:text-primary enabled:hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/30"
                  onClick={() => void sendOtp()}
                >
                  {cooldown > 0 ? `Resend in 0:${String(cooldown).padStart(2, "0")}` : "Resend code"}
                </button>
              </div>
              <div
                className="flex justify-between gap-2"
                id="otp-inputs"
                role="group"
                aria-labelledby="otp-label"
              >
                {otpDigits.map((digit, index) => (
                  <input
                    key={index}
                    ref={(element) => {
                      otpRefs.current[index] = element;
                    }}
                    aria-label={`OTP digit ${index + 1}`}
                    aria-invalid={error?.field === "otp"}
                    autoComplete={index === 0 ? "one-time-code" : undefined}
                    className="field-control aspect-square w-full rounded-lg border border-outline-variant bg-surface-container-lowest text-center font-headline-md text-headline-md text-on-surface focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20 aria-[invalid=true]:border-error"
                    inputMode="numeric"
                    maxLength={1}
                    type="text"
                    value={digit}
                    onChange={(event) => setOtpDigit(index, event.target.value)}
                    onKeyDown={(event) => {
                      if (event.key === "Backspace" && !digit && index > 0) {
                        otpRefs.current[index - 1]?.focus();
                      }
                    }}
                    onPaste={(event) => {
                      event.preventDefault();
                      pasteOtp(event.clipboardData.getData("text"));
                    }}
                  />
                ))}
              </div>
              <p
                role="alert"
                className={error?.field === "otp" ? "font-label-md text-label-md text-error" : "hidden"}
              >
                {error?.field === "otp" ? error.message : ""}
              </p>
            </div>
          ) : null}

          {error?.field === "form" ? (
            <p className="font-label-md text-label-md text-error" role="alert">
              {error.message}
            </p>
          ) : null}
        </form>
      </main>

      <nav className="fixed bottom-0 left-0 right-0 z-50 border-t border-outline-variant/40 bg-surface/90 backdrop-blur-md">
        <div className="mx-auto max-w-2xl px-gutter py-4">
          <button
            form="verify-form"
            type="submit"
            disabled={isVerifying}
            className="flex h-14 w-full items-center justify-center gap-2 rounded-lg bg-primary text-on-primary transition-all duration-150 hover:brightness-110 active:scale-[0.98] disabled:cursor-not-allowed disabled:opacity-70"
          >
            <span className="font-label-md text-label-md">
              {isVerifying ? "Verifying..." : "Continue"}
            </span>
            {!isVerifying ? (
              <ArrowForwardIcon className="h-5 w-5" />
            ) : null}
          </button>
        </div>
      </nav>
    </div>
  );
}

function formatPhone(value: string) {
  const digits = value.replace(/\D/g, "").slice(0, 8);
  return digits.length > 4 ? `${digits.slice(0, 4)} ${digits.slice(4)}` : digits;
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

function SmsIcon({ className }: { className: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path
        d="M5 5h14v10H9l-4 4V5Z"
        stroke="currentColor"
        strokeLinejoin="round"
        strokeWidth="2"
      />
      <path
        d="M9 10h.01M12 10h.01M15 10h.01"
        stroke="currentColor"
        strokeLinecap="round"
        strokeWidth="2.4"
      />
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
