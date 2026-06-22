import { context } from "@opentelemetry/api";
import { W3CTraceContextPropagator } from "@opentelemetry/core";
import { OTLPTraceExporter } from "@opentelemetry/exporter-trace-otlp-http";
import { BatchSpanProcessor, WebTracerProvider } from "@opentelemetry/sdk-trace-web";

let initialized = false;

export function initializeBrowserTracing(): boolean {
  if (initialized || typeof window === "undefined") {
    return true;
  }

  try {
    const endpoint = process.env.NEXT_PUBLIC_OTEL_EXPORTER_OTLP_TRACES_ENDPOINT;
    const provider = new WebTracerProvider({
      spanProcessors: endpoint
        ? [
            new BatchSpanProcessor(
              new OTLPTraceExporter({
                url: endpoint,
                headers: parseHeaders(process.env.NEXT_PUBLIC_OTEL_EXPORTER_OTLP_TRACES_HEADERS),
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
  initializeBrowserTracing();
  return context.active();
}

function parseHeaders(raw: string | undefined): Record<string, string> {
  if (!raw) {
    return {};
  }
  return raw.split(",").reduce<Record<string, string>>((headers, item) => {
    const index = item.indexOf("=");
    if (index <= 0) {
      return headers;
    }
    headers[item.slice(0, index).trim()] = item.slice(index + 1).trim();
    return headers;
  }, {});
}
