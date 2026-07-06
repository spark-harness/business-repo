import { afterEach, describe, expect, it, vi } from "vitest";

import {
  createRequestLogContext,
  createServerLogger,
  type ServerLogField,
} from "./server-logger";
import { emitServerOtelLog } from "./server-otel-logs";

vi.mock("./server-otel-logs", () => ({
  emitServerOtelLog: vi.fn(),
}));

describe("server logger", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.restoreAllMocks();
  });

  it("writes a single JSON log with stable service fields", () => {
    vi.stubEnv("FIDES_RUNTIME_ENV", "sta");
    const sink = vi.fn();
    const logger = createServerLogger({ sink });

    logger.info("bff_proxy.request", {
      route: "/api/v1/:path*",
      status: 201,
      latency_ms: 12,
      request_id: "req_123",
    });

    expect(sink).toHaveBeenCalledTimes(1);
    const record = JSON.parse(sink.mock.calls[0]?.[0] as string) as Record<string, unknown>;
    expect(record).toMatchObject({
      service: "fides-web",
      deployment_environment: "sta",
      level: "INFO",
      operation: "bff_proxy.request",
      route: "/api/v1/:path*",
      status: 201,
      latency_ms: 12,
      request_id: "req_123",
    });
    expect(record.timestamp).toEqual(expect.any(String));
    expect(emitServerOtelLog).toHaveBeenCalledWith(expect.objectContaining(record), sink);
  });

  it("extracts trace context from W3C traceparent", () => {
    const context = createRequestLogContext(
      new Headers({
        traceparent: "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
      }),
    );

    expect(context).toMatchObject({
      trace_id: "4bf92f3577b34da6a3ce929d0e0e4736",
    });
    expect(context).not.toHaveProperty("span_id");
    expect(context.request_id).toEqual(expect.any(String));
  });

  it("uses an existing request id when trace context is absent", () => {
    const context = createRequestLogContext(new Headers({ "x-request-id": "req_existing" }));

    expect(context).toEqual({ request_id: "req_existing" });
  });

  it("ignores unsafe request ids from external headers", () => {
    const oversizedContext = createRequestLogContext(
      new Headers({ "x-request-id": "a".repeat(81) }),
    );
    const tokenContext = createRequestLogContext(
      new Headers({ "x-request-id": "Bearer-secret-token" }),
    );
    const phoneContext = createRequestLogContext(new Headers({ "x-request-id": "91989999" }));

    expect(oversizedContext.request_id).toMatch(/^req_/);
    expect(tokenContext.request_id).toMatch(/^req_/);
    expect(phoneContext.request_id).toMatch(/^req_/);
  });

  it("rejects sensitive or unapproved fields before writing", () => {
    const sink = vi.fn();
    const logger = createServerLogger({ sink });

    expect(() =>
      logger.info("bff_proxy.request", {
        authorization: "Bearer secret",
      } as unknown as Record<string, ServerLogField>),
    ).toThrow(/not allowed/);
    expect(() =>
      logger.info("bff_proxy.request", {
        body: { phone: "91989999", otp: "123456" },
      } as unknown as Record<string, ServerLogField>),
    ).toThrow(/not allowed/);
    expect(sink).not.toHaveBeenCalled();
  });
});
