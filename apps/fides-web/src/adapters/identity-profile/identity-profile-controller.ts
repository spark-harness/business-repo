import type {
  IdentityProfileDraftStore,
  IdentityProfileGateway,
  IdentityProfileInput,
  LoadIdentityProfileResult,
  SaveIdentityProfileResult,
} from "@/application/identity-profile/identity-profile-gateway";
import {
  type IdentityProfileValidationError,
  normalizeIdentityProfile,
} from "@/domain/identity-profile/identity-profile";

type ControllerResult<T> =
  | { ok: true; value: T }
  | { ok: false; error: IdentityProfileValidationError };

export type IdentityProfileController = {
  load(): Promise<ControllerResult<LoadIdentityProfileResult>>;
  save(input: IdentityProfileInput): Promise<ControllerResult<SaveIdentityProfileResult & { shouldNavigate: false }>>;
};

export function createIdentityProfileController(
  gateway: IdentityProfileGateway,
  store: IdentityProfileDraftStore,
  createIdempotencyKey: () => string = defaultIdempotencyKey,
): IdentityProfileController {
  return {
    async load() {
      try {
        const pointer = await store.loadDraftPointer();
        if (!pointer?.applicationId) {
          throw { field: "form", message: "Complete the loan request first." };
        }
        return { ok: true, value: await gateway.load(pointer.applicationId) };
      } catch (error) {
        return { ok: false, error: toIdentityProfileError(error) };
      }
    },

    async save(input) {
      try {
        const pointer = await store.loadDraftPointer();
        if (!pointer?.applicationId) {
          throw { field: "form", message: "Complete the loan request first." };
        }
        const result = await gateway.save({
          applicationId: pointer.applicationId,
          profile: normalizeIdentityProfile(input),
          idempotencyKey: createIdempotencyKey(),
        });
        await store.saveDraftPointer({
          ...pointer,
          currentStep: result.currentStep,
        });
        return { ok: true, value: { ...result, shouldNavigate: false } };
      } catch (error) {
        return { ok: false, error: toIdentityProfileError(error) };
      }
    },
  };
}

function toIdentityProfileError(error: unknown): IdentityProfileValidationError {
  if (error && typeof error === "object") {
    const record = error as Record<string, unknown>;
    return {
      field: isField(record.field) ? record.field : "form",
      message:
        typeof record.message === "string" && record.message.length > 0
          ? record.message
          : "Request failed. Please try again.",
    };
  }
  return { field: "form", message: "Request failed. Please try again." };
}

function isField(value: unknown): value is IdentityProfileValidationError["field"] {
  return (
    value === "hkidBody" ||
    value === "hkidCheckDigit" ||
    value === "firstName" ||
    value === "lastName" ||
    value === "chineseName" ||
    value === "nationality" ||
    value === "dateOfBirth" ||
    value === "form"
  );
}

function defaultIdempotencyKey(): string {
  return globalThis.crypto?.randomUUID?.() ?? `idem-${Date.now()}-${Math.random()}`;
}
