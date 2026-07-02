import type {
  IdentityProfileGateway,
  LoadIdentityProfileResult,
  SaveIdentityProfileResult,
} from "@/application/identity-profile/identity-profile-gateway";
import type { IdentityProfile } from "@/domain/identity-profile/identity-profile";
import {
  defaultFetch,
  generatedClientBasePath,
  readBffErrorEnvelope,
  requestInitWithHeaders,
  timeoutFetch,
  type Fetcher,
} from "@/infrastructure/bff/generated-client";
import {
  Configuration,
  type FidesBffIdentityProfile,
  FidesBffIdentityProfileServiceApi,
  type FidesBffIdentityProfileServiceGetIdentityProfileResponse,
  type FidesBffIdentityProfileServiceUpsertIdentityProfileResponse,
  ResponseError,
} from "@spark-harness/idl-ts-client/vesta/lendora/fides-bff/v1";

type FidesBffIdentityProfileApiFactory = (
  configuration: Configuration,
) => FidesBffIdentityProfileServiceApi;

export class RestIdentityProfileGateway implements IdentityProfileGateway {
  private readonly client: FidesBffIdentityProfileServiceApi;
  private idempotencyKey = "";

  constructor(
    baseUrl = "/api/v1",
    private readonly accessToken: () => string | null,
    fetcher: Fetcher = defaultFetch,
    timeoutMs = 10000,
    clientFactory: FidesBffIdentityProfileApiFactory = (configuration) =>
      new FidesBffIdentityProfileServiceApi(configuration),
  ) {
    this.client = clientFactory(
      new Configuration({
        basePath: generatedClientBasePath(baseUrl),
        fetchApi: timeoutFetch(fetcher, timeoutMs) as typeof fetch,
        middleware: [
          {
            pre: async ({ url, init }) => ({
              url,
              init: requestInitWithHeaders(init, this.requestHeaders(this.idempotencyKey)),
            }),
          },
        ],
      }),
    );
  }

  async save(command: {
    applicationId: string;
    profile: IdentityProfile;
    idempotencyKey: string;
  }): Promise<SaveIdentityProfileResult> {
    return this.call(command.idempotencyKey, async () =>
      toSaveResult(
        await this.client.fidesBffIdentityProfileServiceUpsertIdentityProfile({
          fidesBffIdentityProfileServiceUpsertIdentityProfileRequest: {
            applicationId: command.applicationId,
            profile: toGeneratedProfile(command.profile),
          },
        }),
      ),
    );
  }

  async load(applicationId: string): Promise<LoadIdentityProfileResult> {
    return this.call("", async () =>
      toLoadResult(
        await this.client.fidesBffIdentityProfileServiceGetIdentityProfile({
          applicationId,
        }),
      ),
    );
  }

  private async call<T>(idempotencyKey: string, operation: () => Promise<T>): Promise<T> {
    const token = this.accessToken();
    if (!token) {
      throw { code: "unauthorized", field: "form", message: "Session expired" };
    }
    this.idempotencyKey = idempotencyKey;
    try {
      return await operation();
    } catch (error) {
      if (error instanceof ResponseError) {
        throw await normalizeError(error.response);
      }
      if (error instanceof DOMException && error.name === "AbortError") {
        throw { code: "network_timeout", field: "form", message: "Request timed out" };
      }
      throw error;
    } finally {
      this.idempotencyKey = "";
    }
  }

  private requestHeaders(idempotencyKey: string): Record<string, string> {
    const token = this.accessToken();
    if (!token) {
      return {};
    }
    return {
      Authorization: `Bearer ${token}`,
      ...baseHeaders(idempotencyKey),
    };
  }
}

function toGeneratedProfile(profile: IdentityProfile): FidesBffIdentityProfile {
  return {
    hkidBody: profile.hkidBody,
    hkidCheckDigit: profile.hkidCheckDigit,
    firstName: profile.firstName,
    lastName: profile.lastName,
    chineseName: profile.chineseName,
    nationality: generatedNationality(profile.nationality),
    dateOfBirth: profile.dateOfBirth,
  };
}

function toSaveResult(
  response: FidesBffIdentityProfileServiceUpsertIdentityProfileResponse,
): SaveIdentityProfileResult {
  return {
    profile: toProfile(response.profile),
    currentStep: currentStepValue(response.currentStep),
  };
}

function toLoadResult(
  response: FidesBffIdentityProfileServiceGetIdentityProfileResponse,
): LoadIdentityProfileResult {
  if (response.empty) {
    return { empty: true };
  }
  return { empty: false, profile: toProfile(response.profile) };
}

function toProfile(payload: FidesBffIdentityProfile | undefined): IdentityProfile {
  if (!payload) {
    throw incompleteBffResponse();
  }
  return {
    hkidBody: stringValue(payload.hkidBody),
    hkidCheckDigit: stringValue(payload.hkidCheckDigit),
    firstName: stringValue(payload.firstName),
    lastName: stringValue(payload.lastName),
    chineseName: stringValue(payload.chineseName),
    nationality: domainNationality(payload.nationality),
    dateOfBirth: stringValue(payload.dateOfBirth),
  };
}

function stringValue(value: unknown): string {
  if (typeof value !== "string" || value.length === 0) {
    throw incompleteBffResponse();
  }
  return value;
}

function currentStepValue(value: unknown): string {
  if (typeof value === "string" && value.length > 0) {
    return value;
  }
  if (typeof value !== "number" || !Number.isFinite(value)) {
    throw incompleteBffResponse();
  }
  if (value === 2) {
    return "identity_information";
  }
  return String(value);
}

function generatedNationality(value: IdentityProfile["nationality"]): number {
  const mapped = nationalityToGenerated[value];
  if (!mapped) {
    throw incompleteBffResponse();
  }
  return mapped;
}

function domainNationality(value: unknown): IdentityProfile["nationality"] {
  if (typeof value === "string" && value in nationalityToGenerated) {
    return value as IdentityProfile["nationality"];
  }
  if (typeof value !== "number") {
    throw incompleteBffResponse();
  }
  const mapped = generatedToNationality[value];
  if (!mapped) {
    throw incompleteBffResponse();
  }
  return mapped;
}

const nationalityToGenerated: Record<IdentityProfile["nationality"], number> = {
  chinese: 1,
  hong_kong: 2,
  british: 3,
  indian: 4,
  filipino: 5,
  indonesian: 6,
  pakistani: 7,
  american: 8,
  australian: 9,
  canadian: 10,
  other: 11,
};

const generatedToNationality = Object.fromEntries(
  Object.entries(nationalityToGenerated).map(([key, value]) => [value, key]),
) as Record<number, IdentityProfile["nationality"] | undefined>;

function incompleteBffResponse() {
  return { code: "system_error", field: "form", message: "Incomplete BFF response" };
}

function baseHeaders(idempotencyKey: string): Record<string, string> {
  return idempotencyKey ? { "Idempotency-Key": idempotencyKey } : {};
}

async function normalizeError(response: Response) {
  const envelope = await readBffErrorEnvelope(response);
  if (envelope.code === "unknown") {
    return statusError(response.status);
  }
  return {
    code: envelope.code,
    field: toField(envelope.code),
    message: envelope.message ?? "Request failed. Please try again.",
  };
}

function statusError(status: number) {
  return {
    code: status === 401 ? "unauthorized" : "unknown",
    field: "form",
    message: status === 401 ? "Session expired" : "Request failed. Please try again.",
  };
}

function toField(code: string): string {
  if (code === "hkid_invalid") {
    return "hkidBody";
  }
  if (code === "age_out_of_range") {
    return "dateOfBirth";
  }
  if (code === "validation_error") {
    return "form";
  }
  return "form";
}
