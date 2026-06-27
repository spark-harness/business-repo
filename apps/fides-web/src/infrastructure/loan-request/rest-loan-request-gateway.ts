import type {
  DraftDetail,
  DraftResult,
  LoanRequestGateway,
  LoanRequestInput,
  QuoteResult,
} from "@/application/loan-request/loan-request-gateway";
import { activeContext } from "@/infrastructure/observability/browser-tracing";
import { context, trace } from "@opentelemetry/api";
import type { Context } from "@opentelemetry/api";
import { W3CTraceContextPropagator } from "@opentelemetry/core";

type Fetcher = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;

export class RestLoanRequestGateway implements LoanRequestGateway {
  constructor(
    private readonly baseUrl = "/api/v1",
    private readonly accessToken: () => string | null,
    private readonly fetcher: Fetcher = defaultFetch,
    private readonly timeoutMs = 10000,
  ) {}

  async createQuote(command: {
    loan: LoanRequestInput;
    idempotencyKey: string;
  }): Promise<QuoteResult> {
    const response = await this.post("/pricing/quotes", command.idempotencyKey, {
      productCode: "PIL",
      amount: command.loan.amount,
      term: command.loan.term,
      purpose: command.loan.purpose,
    });
    return toQuoteResult(await response.json());
  }

  async createDraft(command: {
    loan: LoanRequestInput;
    quoteId: string;
    idempotencyKey: string;
  }): Promise<DraftResult> {
    const response = await this.post("/loan-applications", command.idempotencyKey, {
      productCode: "PIL",
      quoteId: command.quoteId,
      loan: command.loan,
      currentStep: "loan_request",
    });
    return toDraftResult(await response.json());
  }

  async getDraft(applicationId: string): Promise<DraftDetail> {
    const response = await this.request(`/loan-applications/${encodeURIComponent(applicationId)}`, {
      method: "GET",
      idempotencyKey: "",
    });
    return toDraftDetail(await response.json());
  }

  async patchDraft(command: {
    applicationId: string;
    loan: LoanRequestInput;
    quoteId: string;
    idempotencyKey: string;
  }): Promise<DraftResult> {
    const response = await this.request(`/loan-applications/${encodeURIComponent(command.applicationId)}`, {
      method: "PATCH",
      idempotencyKey: command.idempotencyKey,
      body: {
        quoteId: command.quoteId,
        loan: command.loan,
        currentStep: "loan_request",
      },
    });
    return toDraftResult(await response.json());
  }

  private async post(path: string, idempotencyKey: string, body: unknown): Promise<Response> {
    return this.request(path, { method: "POST", idempotencyKey, body });
  }

  private async request(
    path: string,
    options: {
      method: "GET" | "POST" | "PATCH";
      idempotencyKey: string;
      body?: unknown;
    },
  ): Promise<Response> {
    const token = this.accessToken();
    if (!token) {
      throw { code: "unauthorized", message: "Session expired" };
    }

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      const headers = {
        Authorization: `Bearer ${token}`,
        ...(options.body === undefined ? {} : { "Content-Type": "application/json" }),
        ...this.traceHeaders(options.idempotencyKey),
      };
      const response = await this.fetcher(`${this.baseUrl}${path}`, {
        method: options.method,
        headers,
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
        signal: controller.signal,
      });
      if (!response.ok) {
        throw await normalizeError(response);
      }
      return response;
    } catch (error) {
      if (error instanceof DOMException && error.name === "AbortError") {
        throw { code: "network_timeout", message: "Request timed out" };
      }
      throw error;
    } finally {
      clearTimeout(timeout);
    }
  }

  private traceHeaders(idempotencyKey: string): Record<string, string> {
    try {
      const baseContext = activeContext();
      const tracer = trace.getTracer("fides.loan-request");
      const span = tracer.startSpan("fides-bff request");
      const requestContext = trace.setSpan(baseContext, span);
      return context.with(requestContext, () => {
        try {
          return traceHeaders(idempotencyKey, requestContext);
        } finally {
          span.end();
        }
      });
    } catch {
      return baseHeaders(idempotencyKey);
    }
  }
}

function defaultFetch(input: RequestInfo | URL, init?: RequestInit) {
  return fetch(input, init);
}

function toQuoteResult(payload: unknown): QuoteResult {
  const record = objectPayload(payload);
  return {
    quoteId: stringField(record, "quoteId"),
    monthly: stringField(record, "monthly"),
    apr: stringField(record, "apr"),
    totalInterest: stringField(record, "totalInterest"),
    totalPayable: stringField(record, "totalPayable"),
    validUntil: stringField(record, "validUntil"),
  };
}

function toDraftResult(payload: unknown): DraftResult {
  const record = objectPayload(payload);
  return {
    applicationId: stringField(record, "applicationId"),
    status: stringField(record, "status"),
    currentStep: stringField(record, "currentStep"),
  };
}

function toDraftDetail(payload: unknown): DraftDetail {
  const record = objectPayload(payload);
  const loan = objectPayload(record.loan);
  const acceptedQuote = objectPayload(record.acceptedQuote);
  return {
    applicationId: stringField(record, "applicationId"),
    status: stringField(record, "status"),
    currentStep: stringField(record, "currentStep"),
    loan: {
      amount: stringField(loan, "amount"),
      term: numberField(loan, "term"),
      purpose: stringField(loan, "purpose"),
    },
    acceptedQuote: toQuoteResult(acceptedQuote),
  };
}

function objectPayload(payload: unknown): Record<string, unknown> {
  if (!payload || typeof payload !== "object") {
    throw { code: "system_error", message: "Incomplete BFF response" };
  }
  return payload as Record<string, unknown>;
}

function stringField(payload: Record<string, unknown>, field: string): string {
  const value = payload[field];
  if (typeof value !== "string" || value.length === 0) {
    throw { code: "system_error", message: "Incomplete BFF response" };
  }
  return value;
}

function numberField(payload: Record<string, unknown>, field: string): number {
  const value = payload[field];
  if (typeof value !== "number" || !Number.isFinite(value)) {
    throw { code: "system_error", message: "Incomplete BFF response" };
  }
  return value;
}

function traceHeaders(idempotencyKey: string, requestContext: Context): Record<string, string> {
  const headers = baseHeaders(idempotencyKey);
  try {
    new W3CTraceContextPropagator().inject(requestContext, headers, {
      set(carrier, key, value) {
        carrier[key] = value;
      },
    });
  } catch {
    return headers;
  }
  const traceId = trace.getSpanContext(requestContext)?.traceId;
  if (traceId && traceId !== "00000000000000000000000000000000") {
    headers["X-Trace-Id"] = traceId;
    return headers;
  }
  const fallbackTraceId = randomHex(16);
  headers.traceparent = `00-${fallbackTraceId}-${randomHex(8)}-01`;
  headers["X-Trace-Id"] = fallbackTraceId;
  return headers;
}

function baseHeaders(idempotencyKey: string): Record<string, string> {
  return idempotencyKey ? { "Idempotency-Key": idempotencyKey } : {};
}

function randomHex(byteLength: number): string {
  const bytes = new Uint8Array(byteLength);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function normalizeError(response: Response) {
  const envelope = await readErrorEnvelope(response);
  if (envelope.code === "unknown" && response.status === 401) {
    return { code: "unauthorized", message: "Session expired" };
  }
  return envelope;
}

async function readErrorEnvelope(response: Response): Promise<{
  code: string;
  message?: string;
  traceId?: string;
}> {
  try {
    const payload = (await response.json()) as unknown;
    if (
      payload &&
      typeof payload === "object" &&
      "error" in payload &&
      payload.error &&
      typeof payload.error === "object"
    ) {
      const error = payload.error as Record<string, unknown>;
      return {
        code: String(error.code ?? "unknown"),
        message: typeof error.message === "string" ? error.message : undefined,
        traceId: typeof error.traceId === "string" ? error.traceId : undefined,
      };
    }
  } catch {
    return { code: "unknown", message: "Request failed" };
  }
  return { code: "unknown", message: "Request failed" };
}
