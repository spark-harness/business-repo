import { afterEach, describe, expect, it, vi } from "vitest";

describe("initializeBrowserTracing", () => {
  afterEach(() => {
    vi.doUnmock("@opentelemetry/sdk-trace-web");
    vi.doUnmock("@opentelemetry/exporter-trace-otlp-http");
    vi.doUnmock("@opentelemetry/instrumentation");
    vi.doUnmock("@opentelemetry/instrumentation-fetch");
  });

  it("does not throw when OpenTelemetry registration fails", async () => {
    vi.resetModules();
    vi.doMock("@opentelemetry/sdk-trace-web", async (importOriginal) => {
      const actual = await importOriginal<typeof import("@opentelemetry/sdk-trace-web")>();
      class FailingWebTracerProvider extends actual.WebTracerProvider {
        override register() {
          throw new Error("otel registration failed");
        }
      }
      return {
        ...actual,
        WebTracerProvider: FailingWebTracerProvider,
      };
    });
    const { initializeBrowserTracing } = await import("./browser-tracing");

    expect(initializeBrowserTracing({ headers: {} })).toBe(false);
    expect(initializeBrowserTracing({ headers: {} })).toBe(true);
  });

  it("uses runtime public config for browser tracing exporter", async () => {
    vi.resetModules();
    const exporter = vi.fn();
    let providerOptions: Record<string, unknown> | undefined;
    vi.doMock("@opentelemetry/exporter-trace-otlp-http", () => ({
      OTLPTraceExporter: exporter,
    }));
    const registerInstrumentations = vi.fn();
    const FetchInstrumentation = vi.fn(function FetchInstrumentation(this: object, options: object) {
      Object.assign(this, { options });
    });
    vi.doMock("@opentelemetry/instrumentation", () => ({
      registerInstrumentations,
    }));
    vi.doMock("@opentelemetry/instrumentation-fetch", () => ({
      FetchInstrumentation,
    }));
    const BatchSpanProcessor = vi.fn(function BatchSpanProcessor() {});
    const WebTracerProvider = vi.fn(function WebTracerProvider(
      this: { register: () => void },
      options: Record<string, unknown>,
    ) {
      providerOptions = options;
      this.register = vi.fn();
    });
    vi.doMock("@opentelemetry/sdk-trace-web", () => ({
      BatchSpanProcessor,
      WebTracerProvider,
    }));
    const { initializeBrowserTracing } = await import("./browser-tracing");

    expect(
      initializeBrowserTracing({
        endpoint: "https://otel.example/v1/traces",
        environment: "sta",
        headers: { "x-lendora-environment": "sta" },
      }),
    ).toBe(true);
    expect(exporter).toHaveBeenCalledWith({
      url: "https://otel.example/v1/traces",
      headers: { "x-lendora-environment": "sta" },
    });
    expect(providerOptions?.resource).toMatchObject({
      attributes: {
        "deployment.environment": "sta",
        "service.name": "fides",
      },
    });
    expect(FetchInstrumentation).toHaveBeenCalledWith({
      propagateTraceHeaderCorsUrls: [/^\/api\/v1(?:\/|$)/],
    });
    expect(registerInstrumentations).toHaveBeenCalledWith({
      instrumentations: [expect.any(FetchInstrumentation)],
    });
  });
});
