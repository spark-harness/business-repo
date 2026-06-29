import { describe, expect, it, vi } from "vitest";

import { RestIdentityProfileGateway } from "./rest-identity-profile-gateway";

describe("RestIdentityProfileGateway", () => {
  it("sends PUT identity profile to BFF with application id and idempotency key", async () => {
    let request: { input: RequestInfo | URL; init?: RequestInit } | undefined;
    const gateway = new RestIdentityProfileGateway(
      "/api/v1",
      () => "token",
      async (input, init) => {
        request = { input, init };
        return new Response(
          JSON.stringify({
            profile: generatedProfile(),
            currentStep: "identity_information",
          }),
          { status: 200 },
        );
      },
    );

    const result = await gateway.save({
      applicationId: "app_001",
      profile: validProfile(),
      idempotencyKey: "idem-1",
    });

    expect(result.currentStep).toBe("identity_information");
    expect(request?.input).toBe("/api/v1/me/identity-profile");
    expect((request?.init?.headers as Record<string, string>).Authorization).toBe("Bearer token");
    expect((request?.init?.headers as Record<string, string>)["Idempotency-Key"]).toBe("idem-1");
    expect(JSON.parse(String(request?.init?.body))).toMatchObject({
      applicationId: "app_001",
      profile: { nationality: 2 },
    });
  });

  it("maps empty GET response", async () => {
    const gateway = new RestIdentityProfileGateway(
      "/api/v1",
      () => "token",
      async () => new Response(JSON.stringify({ empty: true }), { status: 200 }),
    );

    await expect(gateway.load("app_001")).resolves.toEqual({ empty: true });
  });

  it("maps generated numeric nationality back to domain value", async () => {
    const gateway = new RestIdentityProfileGateway(
      "/api/v1",
      () => "token",
      async () => new Response(JSON.stringify({ empty: false, profile: generatedProfile() }), { status: 200 }),
    );

    await expect(gateway.load("app_001")).resolves.toEqual({
      empty: false,
      profile: validProfile(),
    });
  });

  it("rejects requests without an access token before calling the generated client", async () => {
    const fetcher = vi.fn();
    const gateway = new RestIdentityProfileGateway("/api/v1", () => null, fetcher);

    await expect(gateway.load("app_001")).rejects.toMatchObject({ code: "unauthorized" });
    expect(fetcher).not.toHaveBeenCalled();
  });
});

function validProfile() {
  return {
    hkidBody: "A123456",
    hkidCheckDigit: "3",
    firstName: "Ada",
    lastName: "Lovelace",
    chineseName: "Test Name",
    nationality: "hong_kong" as const,
    dateOfBirth: "1990-01-15",
  };
}

function generatedProfile() {
  return {
    hkidBody: "A123456",
    hkidCheckDigit: "3",
    firstName: "Ada",
    lastName: "Lovelace",
    chineseName: "Test Name",
    nationality: 2,
    dateOfBirth: "1990-01-15",
  };
}
