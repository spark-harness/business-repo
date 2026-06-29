"use client";

import { useEffect, useState } from "react";
import type { ReactNode } from "react";

import type { IdentityProfileController } from "@/adapters/identity-profile/identity-profile-controller";
import {
  type IdentityProfileField,
  type IdentityProfileInput,
  nationalityOptions,
  validateIdentityProfile,
} from "@/domain/identity-profile/identity-profile";

type IdentityProfileScreenProps = {
  controller: IdentityProfileController;
};

type FormError = {
  field: IdentityProfileField;
  message: string;
};

const emptyProfile: IdentityProfileInput = {
  hkidBody: "",
  hkidCheckDigit: "",
  firstName: "",
  lastName: "",
  chineseName: "",
  nationality: "",
  dateOfBirth: "",
};

export function IdentityProfileScreen({ controller }: IdentityProfileScreenProps) {
  const [profile, setProfile] = useState<IdentityProfileInput>(emptyProfile);
  const [error, setError] = useState<FormError | null>(null);
  const [isSaving, setIsSaving] = useState(false);

  useEffect(() => {
    let cancelled = false;
    void controller.load().then((result) => {
      if (cancelled) {
        return;
      }
      if (!result.ok) {
        setError(result.error);
        return;
      }
      if (!result.value.empty && result.value.profile) {
        setProfile(result.value.profile);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [controller]);

  async function save() {
    setError(null);
    const validationError = validateIdentityProfile(profile);
    if (validationError) {
      setError(validationError);
      return;
    }
    setIsSaving(true);
    try {
      const result = await controller.save(profile);
      if (!result.ok) {
        setError(result.error);
      }
    } finally {
      setIsSaving(false);
    }
  }

  function update(field: keyof IdentityProfileInput, value: string) {
    setProfile((current) => ({ ...current, [field]: value }));
    if (error?.field === field || (field === "hkidCheckDigit" && error?.field === "hkidBody")) {
      setError(null);
    }
  }

  return (
    <div className="min-h-[max(884px,100dvh)] overflow-x-hidden bg-background text-on-background">
      <header className="fixed left-0 right-0 top-0 z-50 bg-surface/80 backdrop-blur-md">
        <div className="mx-auto flex h-16 w-full max-w-2xl items-center justify-center px-gutter">
          <span className="font-headline-md text-[19px] font-semibold text-on-surface">Lendora</span>
        </div>
        <div className="h-1 w-full bg-surface-container-high" role="progressbar" aria-label="Application progress" aria-valuemin={1} aria-valuemax={7} aria-valuenow={3} aria-valuetext="Step 3 of 7">
          <div className="h-full w-[42.85%] rounded-r-full bg-primary" />
        </div>
      </header>

      <main className="mx-auto flex w-full max-w-2xl flex-col gap-stack-lg px-container-padding-mobile pb-36 pt-24">
        <section className="flex flex-col gap-2">
          <h1 className="font-headline-lg-mobile text-headline-lg-mobile text-on-surface md:font-headline-lg md:text-headline-lg">
            Identity information
          </h1>
          <p className="font-body-md text-body-md text-on-surface-variant">
            Confirm the details exactly as shown on your HKID.
          </p>
        </section>

        <form
          noValidate
          className="flex flex-col gap-stack-md"
          onSubmit={(event) => {
            event.preventDefault();
            void save();
          }}
        >
          <div className="grid grid-cols-[minmax(0,1fr)_88px] gap-3">
            <Field label="HKID" error={error?.field === "hkidBody" ? error.message : undefined}>
              <input
                aria-label="HKID body"
                className="field-control h-14 w-full rounded-lg border border-outline-variant bg-surface-container-lowest px-4 font-body-md text-body-md uppercase text-on-surface focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20"
                maxLength={7}
                value={profile.hkidBody}
                onChange={(event) => update("hkidBody", event.target.value.toUpperCase())}
              />
            </Field>
            <Field label="Digit" error={undefined}>
              <input
                aria-label="HKID check digit"
                className="field-control h-14 w-full rounded-lg border border-outline-variant bg-surface-container-lowest px-4 text-center font-body-md text-body-md uppercase text-on-surface focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20"
                maxLength={1}
                value={profile.hkidCheckDigit}
                onChange={(event) => update("hkidCheckDigit", event.target.value.toUpperCase())}
              />
            </Field>
          </div>

          <Field label="First Name" error={error?.field === "firstName" ? error.message : undefined}>
            <input aria-label="First Name" className="field-control h-14 w-full rounded-lg border border-outline-variant bg-surface-container-lowest px-4 font-body-md text-body-md text-on-surface focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20" value={profile.firstName} onChange={(event) => update("firstName", event.target.value)} />
          </Field>
          <Field label="Last Name" error={error?.field === "lastName" ? error.message : undefined}>
            <input aria-label="Last Name" className="field-control h-14 w-full rounded-lg border border-outline-variant bg-surface-container-lowest px-4 font-body-md text-body-md text-on-surface focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20" value={profile.lastName} onChange={(event) => update("lastName", event.target.value)} />
          </Field>
          <Field label="Chinese Name" error={error?.field === "chineseName" ? error.message : undefined}>
            <input aria-label="Chinese Name" className="field-control h-14 w-full rounded-lg border border-outline-variant bg-surface-container-lowest px-4 font-body-md text-body-md text-on-surface focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20" value={profile.chineseName} onChange={(event) => update("chineseName", event.target.value)} />
          </Field>
          <Field label="Nationality" error={error?.field === "nationality" ? error.message : undefined}>
            <select aria-label="Nationality" className="field-control h-14 w-full rounded-lg border border-outline-variant bg-surface-container-lowest px-4 font-body-md text-body-md text-on-surface focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20" value={profile.nationality} onChange={(event) => update("nationality", event.target.value)}>
              <option value="">Select nationality</option>
              {nationalityOptions().map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Date of Birth" error={error?.field === "dateOfBirth" ? error.message : undefined}>
            <input aria-label="Date of Birth" className="field-control h-14 w-full rounded-lg border border-outline-variant bg-surface-container-lowest px-4 font-body-md text-body-md text-on-surface focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/20" type="date" value={profile.dateOfBirth} onChange={(event) => update("dateOfBirth", event.target.value)} />
          </Field>

          <p role="alert" className={error?.field === "form" ? "font-label-md text-label-md text-error" : "hidden"}>
            {error?.field === "form" ? error.message : ""}
          </p>
        </form>
      </main>

      <footer className="fixed bottom-0 left-0 right-0 z-40 border-t border-outline-variant bg-surface/90 px-container-padding-mobile py-4 backdrop-blur-md">
        <div className="mx-auto max-w-2xl">
          <button
            className="h-14 w-full rounded-full bg-primary px-6 font-label-lg text-label-lg text-on-primary transition-opacity disabled:opacity-50"
            disabled={isSaving}
            type="submit"
            onClick={() => void save()}
          >
            {isSaving ? "Saving..." : "Continue"}
          </button>
        </div>
      </footer>
    </div>
  );
}

function Field({
  label,
  error,
  children,
}: {
  label: string;
  error?: string;
  children: ReactNode;
}) {
  return (
    <label className="flex flex-col gap-2">
      <span className="font-label-md text-label-md text-on-surface-variant">{label}</span>
      {children}
      <span role="alert" className={error ? "font-label-md text-label-md text-error" : "hidden"}>
        {error ?? ""}
      </span>
    </label>
  );
}
