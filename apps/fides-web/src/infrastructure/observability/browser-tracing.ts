import { context } from "@opentelemetry/api";
import { W3CTraceContextPropagator } from "@opentelemetry/core";
import { OTLPTraceExporter } from "@opentelemetry/exporter-trace-otlp-http";
import { resourceFromAttributes } from "@opentelemetry/resources";
import { BatchSpanProcessor, WebTracerProvider } from "@opentelemetry/sdk-trace-web";

import type { BrowserTracingRuntimeConfig } from "@/api/runtime-config/public-runtime-config";

let initialized = false;
const SERVICE_NAME = "fides";

export function initializeBrowserTracing(config: BrowserTracingRuntimeConfig): boolean {
  if (initialized || typeof window === "undefined") {
    return true;
  }

  try {
    const provider = new WebTracerProvider({
      resource: resourceFromAttributes({
        "service.name": SERVICE_NAME,
        ...(config.environment ? { "deployment.environment": config.environment } : {}),
      }),
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
