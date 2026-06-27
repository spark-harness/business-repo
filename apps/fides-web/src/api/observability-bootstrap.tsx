"use client";

import { useEffect } from "react";

import { initializeBrowserTracing } from "@/infrastructure/observability/browser-tracing";
import type { PublicRuntimeConfig } from "@/api/runtime-config/public-runtime-config";

type ObservabilityBootstrapProps = {
  config: PublicRuntimeConfig;
};

export function ObservabilityBootstrap({ config }: ObservabilityBootstrapProps) {
  useEffect(() => {
    initializeBrowserTracing(config.browserTracing);
  }, [config]);

  return null;
}
