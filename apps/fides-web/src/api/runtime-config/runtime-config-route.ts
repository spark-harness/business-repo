import { NextResponse } from "next/server";

import { createRequestLogContext, serverLogger } from "@/infrastructure/observability/server-logger";

import { getPublicRuntimeConfig } from "./get-public-runtime-config";

export async function getRuntimeConfigResponse(request: Request) {
  const startedAt = performance.now();
  const logContext = createRequestLogContext(request.headers);

  try {
    const response = NextResponse.json(await getPublicRuntimeConfig(), {
      headers: {
        "Cache-Control": "no-store",
      },
    });

    serverLogger.info("runtime_config.request", {
      ...logContext,
      route: "/api/runtime-config",
      status: response.status,
      latency_ms: elapsedMs(startedAt),
      config_source: "env",
    });
    return response;
  } catch (error) {
    serverLogger.error("runtime_config.error", {
      ...logContext,
      route: "/api/runtime-config",
      latency_ms: elapsedMs(startedAt),
      error_code: "FIDES-SYSTEM-0001",
      error_type: error instanceof Error ? error.name : "UnknownError",
    });
    throw error;
  }
}

function elapsedMs(startedAt: number): number {
  return Math.max(0, Math.round(performance.now() - startedAt));
}
