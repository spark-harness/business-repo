import type { DraftStore } from "@/application/loan-request/loan-request-gateway";
import type { IdentityProfile, IdentityProfileInput } from "@/domain/identity-profile/identity-profile";

export type SaveIdentityProfileResult = {
  profile: IdentityProfile;
  currentStep: string;
};

export type LoadIdentityProfileResult = {
  empty: boolean;
  profile?: IdentityProfile;
};

export interface IdentityProfileGateway {
  save(command: {
    applicationId: string;
    profile: IdentityProfile;
    idempotencyKey: string;
  }): Promise<SaveIdentityProfileResult>;

  load(applicationId: string): Promise<LoadIdentityProfileResult>;
}

export type IdentityProfileDraftStore = DraftStore;
export type { IdentityProfileInput };
