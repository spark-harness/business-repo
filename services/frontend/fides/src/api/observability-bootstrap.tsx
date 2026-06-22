"use client";

import { useEffect } from "react";

import { initializeBrowserTracing } from "@/infrastructure/observability/browser-tracing";

export function ObservabilityBootstrap() {
  useEffect(() => {
    initializeBrowserTracing();
  }, []);

  return null;
}
