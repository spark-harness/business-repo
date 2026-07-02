import { NextResponse } from "next/server";

import { getBffProxyBaseUrl } from "@/infrastructure/bff/proxy-config";

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

type ProxyContext = {
  params: Promise<{ path?: string[] }>;
};

export async function proxyBffRequest(request: Request, context: ProxyContext): Promise<Response> {
  const { path = [] } = await context.params;
  const targetBaseUrl = await getBffProxyBaseUrl();
  const sourceUrl = new URL(request.url);
  const targetUrl = new URL(`${targetBaseUrl}/${path.map(encodeURIComponent).join("/")}`);
  targetUrl.search = sourceUrl.search;

  const response = await fetch(targetUrl, {
    method: request.method,
    headers: forwardHeaders(request.headers),
    body: request.method === "GET" || request.method === "HEAD" ? undefined : request.body,
    redirect: "manual",
    duplex: "half",
  } as RequestInit & { duplex: "half" });

  return new NextResponse(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers: forwardHeaders(response.headers),
  });
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
