import { NextResponse } from "next/server";

import { getBffProxyBaseUrl } from "@/infrastructure/bff/proxy-config";
import { createRequestLogContext, serverLogger } from "@/infrastructure/observability/server-logger";

const HOP_BY_HOP_HEADERS = new Set([
  "connection",
  "content-length",
  "host",
  "keep-alive",
  "proxy-authenticate",
  "proxy-authorization",
  "te",
  "trailer",
  "transfer-encoding",
  "upgrade",
]);
const BFF_PROXY_TIMEOUT_MS = 10_000;

type ProxyContext = {
  params: Promise<{ path?: string[] }>;
};

export async function proxyBffRequest(request: Request, context: ProxyContext): Promise<Response> {
  const startedAt = performance.now();
  const logContext = createRequestLogContext(request.headers);
  const { path = [] } = await context.params;
  const targetBaseUrl = await getBffProxyBaseUrl();
  const sourceUrl = new URL(request.url);
  const targetUrl = new URL(`${targetBaseUrl}/${path.map(encodeURIComponent).join("/")}`);
  targetUrl.search = sourceUrl.search;

  let response: Response;
  const abortController = new AbortController();
  const timeout = setTimeout(() => abortController.abort(), BFF_PROXY_TIMEOUT_MS);
  try {
    response = await fetch(targetUrl, {
      method: request.method,
      headers: forwardHeaders(request.headers),
      body: request.method === "GET" || request.method === "HEAD" ? undefined : request.body,
      redirect: "manual",
      signal: abortController.signal,
      duplex: "half",
    } as RequestInit & { duplex: "half" });
  } catch (error) {
    serverLogger.error("bff_proxy.error", {
      ...logContext,
      route: "/api/v1/:path*",
      latency_ms: elapsedMs(startedAt),
      error_code: "FIDES-DEPENDENCY-0001",
      error_type: abortController.signal.aborted ? "TimeoutError" : errorType(error),
    });
    throw error;
  } finally {
    clearTimeout(timeout);
  }

  const logFields = {
    ...logContext,
    route: "/api/v1/:path*",
    status: response.status,
    latency_ms: elapsedMs(startedAt),
  };

  if (response.status >= 500) {
    serverLogger.error("bff_proxy.error", {
      ...logFields,
      error_code: "FIDES-DEPENDENCY-0001",
      error_type: "HttpStatus",
    });
  } else if (response.status >= 400) {
    serverLogger.warn("bff_proxy.warn", {
      ...logFields,
      error_code: "FIDES-DEPENDENCY-0001",
      error_type: "HttpStatus",
    });
  } else {
    serverLogger.info("bff_proxy.request", logFields);
  }

  return new NextResponse(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers: forwardHeaders(response.headers),
  });
}

function elapsedMs(startedAt: number): number {
  return Math.max(0, Math.round(performance.now() - startedAt));
}

function errorType(error: unknown): string {
  return error instanceof Error ? error.name : "UnknownError";
}

function forwardHeaders(headers: Headers): Headers {
  const forwarded = new Headers();
  headers.forEach((value, key) => {
    if (!HOP_BY_HOP_HEADERS.has(key.toLowerCase())) {
      forwarded.set(key, value);
    }
  });
  return forwarded;
}
