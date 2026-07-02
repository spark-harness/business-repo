import { beforeEach, describe, expect, it, vi } from "vitest";

import { proxyBffRequest } from "./bff-proxy-route";

vi.mock("@/infrastructure/bff/proxy-config", () => ({
  getBffProxyBaseUrl: vi.fn(async () => "http://fides-bff:8000/api/v1"),
}));

describe("proxyBffRequest", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it("forwards method, path, query, headers and body to the internal BFF", async () => {
    const fetcher = vi.fn(async () => new Response(JSON.stringify({ ok: true }), { status: 201 }));
    vi.stubGlobal("fetch", fetcher);

    const response = await proxyBffRequest(
      new Request("http://fides-web.local/api/v1/loan-applications?expand=quote", {
        method: "POST",
        headers: {
          Authorization: "Bearer token",
          "Idempotency-Key": "idem-1",
          "Content-Type": "application/json",
          Host: "fides-web.local",
        },
        body: JSON.stringify({ quoteId: "quote_1" }),
      }),
      { params: Promise.resolve({ path: ["loan-applications"] }) },
    );

    expect(response.status).toBe(201);
    expect(fetcher).toHaveBeenCalledWith(
      new URL("http://fides-bff:8000/api/v1/loan-applications?expand=quote"),
      expect.objectContaining({
        method: "POST",
        body: expect.any(ReadableStream),
      }),
    );
    const init = fetcher.mock.calls[0]?.[1] as RequestInit;
    const headers = init.headers as Headers;
    expect(headers.get("Authorization")).toBe("Bearer token");
    expect(headers.get("Idempotency-Key")).toBe("idem-1");
    expect(headers.get("Host")).toBeNull();
  });
});
