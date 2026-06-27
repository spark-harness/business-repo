import { afterEach, describe, expect, it, vi } from "vitest";

describe("initializeBrowserTracing", () => {
  afterEach(() => {
    vi.doUnmock("@opentelemetry/sdk-trace-web");
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
    vi.doMock("@opentelemetry/exporter-trace-otlp-http", () => ({
      OTLPTraceExporter: exporter,
    }));
    const { initializeBrowserTracing } = await import("./browser-tracing");

    expect(
      initializeBrowserTracing({
        endpoint: "https://otel.example/v1/traces",
        headers: { "x-lendora-environment": "sta" },
      }),
    ).toBe(true);
    expect(exporter).toHaveBeenCalledWith({
      url: "https://otel.example/v1/traces",
      headers: { "x-lendora-environment": "sta" },
    });
  });
});
