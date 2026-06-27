import { context } from "@opentelemetry/api";
import { W3CTraceContextPropagator } from "@opentelemetry/core";
import { OTLPTraceExporter } from "@opentelemetry/exporter-trace-otlp-http";
import { BatchSpanProcessor, WebTracerProvider } from "@opentelemetry/sdk-trace-web";

import type { BrowserTracingRuntimeConfig } from "@/api/runtime-config/public-runtime-config";

let initialized = false;

export function initializeBrowserTracing(config: BrowserTracingRuntimeConfig): boolean {
  if (initialized || typeof window === "undefined") {
    return true;
  }

  try {
    const provider = new WebTracerProvider({
      spanProcessors: config.endpoint
        ? [
            new BatchSpanProcessor(
              new OTLPTraceExporter({
                url: config.endpoint,
                headers: config.headers,
              }),
            ),
          ]
        : [],
    });
    provider.register({ propagator: new W3CTraceContextPropagator() });
    initialized = true;
    return true;
  } catch {
    initialized = true;
    return false;
  }
}

export function activeContext() {
  return context.active();
}
