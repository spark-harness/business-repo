import type {
  DraftDetail,
  DraftResult,
  LoanRequestGateway,
  LoanRequestInput,
  QuoteResult,
} from "@/application/loan-request/loan-request-gateway";
import {
  defaultFetch,
  generatedClientBasePath,
  readBffErrorEnvelope,
  requestInitWithHeaders,
  timeoutFetch,
  type Fetcher,
} from "@/infrastructure/bff/generated-client";
import {
  Configuration,
  type FidesBffAcceptedQuote,
  FidesBffLoanApplicationServiceApi,
  type FidesBffLoanApplicationServiceCreateLoanApplicationResponse,
  type FidesBffLoanApplicationServiceGetLoanApplicationResponse,
  type FidesBffLoanApplicationServiceUpdateLoanApplicationResponse,
  type FidesBffLoanTerms,
  FidesBffPricingServiceApi,
  type FidesBffPricingServiceCreateQuoteResponse,
  ResponseError,
} from "@spark-harness/idl-ts-client/vesta/lendora/fides-bff/v1";

type PricingApiFactory = (configuration: Configuration) => FidesBffPricingServiceApi;
type LoanApplicationApiFactory = (configuration: Configuration) => FidesBffLoanApplicationServiceApi;

export class RestLoanRequestGateway implements LoanRequestGateway {
  private readonly pricingClient: FidesBffPricingServiceApi;
  private readonly loanApplicationClient: FidesBffLoanApplicationServiceApi;
  private idempotencyKey = "";

  constructor(
    baseUrl = "/api/v1",
    private readonly accessToken: () => string | null,
    fetcher: Fetcher = defaultFetch,
    timeoutMs = 10000,
    pricingApiFactory: PricingApiFactory = (configuration) => new FidesBffPricingServiceApi(configuration),
    loanApplicationApiFactory: LoanApplicationApiFactory = (configuration) =>
      new FidesBffLoanApplicationServiceApi(configuration),
  ) {
    const configuration = new Configuration({
      basePath: generatedClientBasePath(baseUrl),
      fetchApi: timeoutFetch(fetcher, timeoutMs) as typeof fetch,
      middleware: [
        {
          pre: async ({ url, init }) => ({
            url,
            init: requestInitWithHeaders(init, this.requestHeaders(this.idempotencyKey)),
          }),
        },
      ],
    });
    this.pricingClient = pricingApiFactory(configuration);
    this.loanApplicationClient = loanApplicationApiFactory(configuration);
  }

  async createQuote(command: {
    loan: LoanRequestInput;
    idempotencyKey: string;
  }): Promise<QuoteResult> {
    return this.call(command.idempotencyKey, async () =>
      toQuoteResult(
        await this.pricingClient.fidesBffPricingServiceCreateQuote({
          fidesBffPricingServiceCreateQuoteRequest: {
            productCode: "PIL",
            amount: command.loan.amount,
            term: command.loan.term,
            purpose: command.loan.purpose,
          },
        }),
      ),
    );
  }

  async createDraft(command: {
    loan: LoanRequestInput;
    quoteId: string;
    idempotencyKey: string;
  }): Promise<DraftResult> {
    return this.call(command.idempotencyKey, async () =>
      toDraftResult(
        await this.loanApplicationClient.fidesBffLoanApplicationServiceCreateLoanApplication({
          fidesBffLoanApplicationServiceCreateLoanApplicationRequest: {
            productCode: "PIL",
            quoteId: command.quoteId,
            loan: command.loan,
            currentStep: "loan_request",
          },
        }),
      ),
    );
  }

  async getDraft(applicationId: string): Promise<DraftDetail> {
    return this.call("", async () =>
      toDraftDetail(
        await this.loanApplicationClient.fidesBffLoanApplicationServiceGetLoanApplication({
          applicationId,
        }),
      ),
    );
  }

  async patchDraft(command: {
    applicationId: string;
    loan: LoanRequestInput;
    quoteId: string;
    idempotencyKey: string;
  }): Promise<DraftResult> {
    return this.call(command.idempotencyKey, async () =>
      toDraftResult(
        await this.loanApplicationClient.fidesBffLoanApplicationServiceUpdateLoanApplication({
          applicationId: command.applicationId,
          fidesBffLoanApplicationServiceUpdateLoanApplicationRequest: {
            applicationId: command.applicationId,
            quoteId: command.quoteId,
            loan: command.loan,
            currentStep: "loan_request",
          },
        }),
      ),
    );
  }

  private async call<T>(idempotencyKey: string, operation: () => Promise<T>): Promise<T> {
    const token = this.accessToken();
    if (!token) {
      throw { code: "unauthorized", message: "Session expired" };
    }
    this.idempotencyKey = idempotencyKey;
    try {
      return await operation();
    } catch (error) {
      if (error instanceof ResponseError) {
        throw await normalizeError(error.response);
      }
      if (error instanceof DOMException && error.name === "AbortError") {
        throw { code: "network_timeout", message: "Request timed out" };
      }
      throw error;
    } finally {
      this.idempotencyKey = "";
    }
  }

  private requestHeaders(idempotencyKey: string): Record<string, string> {
    const token = this.accessToken();
    if (!token) {
      return {};
    }
    return {
      Authorization: `Bearer ${token}`,
      ...baseHeaders(idempotencyKey),
    };
  }
}

function toQuoteResult(payload: FidesBffPricingServiceCreateQuoteResponse | FidesBffAcceptedQuote): QuoteResult {
  return {
    quoteId: stringValue(payload.quoteId),
    monthly: stringValue(payload.monthly),
    apr: stringValue(payload.apr),
    totalInterest: stringValue(payload.totalInterest),
    totalPayable: stringValue(payload.totalPayable),
    validUntil: stringValue(payload.validUntil),
  };
}

function toDraftResult(
  payload:
    | FidesBffLoanApplicationServiceCreateLoanApplicationResponse
    | FidesBffLoanApplicationServiceUpdateLoanApplicationResponse,
): DraftResult {
  return {
    applicationId: stringValue(payload.applicationId),
    status: stringValue(payload.status),
    currentStep: stringValue(payload.currentStep),
  };
}

function toDraftDetail(payload: FidesBffLoanApplicationServiceGetLoanApplicationResponse): DraftDetail {
  return {
    applicationId: stringValue(payload.applicationId),
    status: stringValue(payload.status),
    currentStep: stringValue(payload.currentStep),
    loan: toLoanRequestInput(payload.loan),
    acceptedQuote: toQuoteResult(requiredAcceptedQuote(payload.acceptedQuote)),
  };
}

function toLoanRequestInput(payload: FidesBffLoanTerms | undefined): LoanRequestInput {
  if (!payload) {
    throw incompleteBffResponse();
  }
  return {
    amount: stringValue(payload.amount),
    term: numberValue(payload.term),
    purpose: stringValue(payload.purpose),
  };
}

function requiredAcceptedQuote(payload: FidesBffAcceptedQuote | undefined): FidesBffAcceptedQuote {
  if (!payload) {
    throw incompleteBffResponse();
  }
  return payload;
}

function stringValue(value: unknown): string {
  if (typeof value !== "string" || value.length === 0) {
    throw incompleteBffResponse();
  }
  return value;
}

function numberValue(value: unknown): number {
  if (typeof value !== "number" || !Number.isFinite(value)) {
    throw incompleteBffResponse();
  }
  return value;
}

function incompleteBffResponse() {
  return { code: "system_error", message: "Incomplete BFF response" };
}

function baseHeaders(idempotencyKey: string): Record<string, string> {
  return idempotencyKey ? { "Idempotency-Key": idempotencyKey } : {};
}

async function normalizeError(response: Response) {
  const envelope = await readBffErrorEnvelope(response);
  if (envelope.code === "unknown" && response.status === 401) {
    return { code: "unauthorized", message: "Session expired" };
  }
  return envelope;
}
