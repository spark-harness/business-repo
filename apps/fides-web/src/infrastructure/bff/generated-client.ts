export type Fetcher = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;

export function defaultFetch(input: RequestInfo | URL, init?: RequestInit) {
  return fetch(input, init);
}

export function generatedClientBasePath(baseUrl: string): string {
  return baseUrl.replace(/\/api\/v1\/?$/, "");
}

export function requestInitWithHeaders(init: RequestInit, headers: Record<string, string>): RequestInit {
  const nextInit: RequestInit = {
    ...init,
    headers: {
      ...(init.headers as Record<string, string> | undefined),
      ...headers,
    },
  };
  if (nextInit.credentials === undefined) {
    delete nextInit.credentials;
  }
  return nextInit;
}

export function timeoutFetch(fetcher: Fetcher, timeoutMs: number): Fetcher {
  return async (input, init) => {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), timeoutMs);
    try {
      return await fetcher(input, {
        ...init,
        signal: controller.signal,
      });
    } finally {
      clearTimeout(timeout);
    }
  };
}

export async function readBffErrorEnvelope(response: Response): Promise<{
  code: string;
  message?: string;
  traceId?: string;
  retryAfterSec?: number;
}> {
  try {
    const payload = (await response.json()) as unknown;
    if (
      payload &&
      typeof payload === "object" &&
      "error" in payload &&
      payload.error &&
      typeof payload.error === "object"
    ) {
      const error = payload.error as Record<string, unknown>;
      return {
        code: String(error.code ?? "unknown"),
        message: typeof error.message === "string" ? error.message : undefined,
        traceId: typeof error.traceId === "string" ? error.traceId : undefined,
        retryAfterSec: typeof error.retryAfterSec === "number" ? error.retryAfterSec : retryAfter(response.headers),
      };
    }
  } catch {
    return { code: response.status >= 500 ? "system_error" : "unknown", retryAfterSec: retryAfter(response.headers) };
  }
  return { code: response.status >= 500 ? "system_error" : "unknown", retryAfterSec: retryAfter(response.headers) };
}

export function retryAfter(headers: Headers): number | undefined {
  const value = headers.get("Retry-After");
  if (!value) {
    return undefined;
  }
  const seconds = Number(value);
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : undefined;
}
