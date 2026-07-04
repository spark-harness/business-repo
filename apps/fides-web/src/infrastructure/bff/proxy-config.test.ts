import { afterEach, describe, expect, it, vi } from "vitest";

import { getBffProxyBaseUrl } from "./proxy-config";

describe("proxy config", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it("loads the internal BFF URL from typed env", () => {
    vi.stubEnv("FIDES_BFF_BASE_URL", "http://fides-bff:8000/api/v1/");

    expect(getBffProxyBaseUrl()).toBe("http://fides-bff:8000/api/v1");
  });
});
