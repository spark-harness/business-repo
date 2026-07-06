import { z } from "zod";

const blankStringToUndefined = (value: unknown) =>
  typeof value === "string" && value.trim() === "" ? undefined : value;

const optionalTrimmedString = z.preprocess(
  blankStringToUndefined,
  z.string().trim().optional(),
);

const requiredTrimmedString = z.string().trim().min(1);
const defaultedTrimmedString = (defaultValue: string) =>
  z.preprocess(blankStringToUndefined, z.string().trim().default(defaultValue));
const headersString = (variableName: string) =>
  optionalTrimmedString.superRefine((value, context) => {
    if (!value) {
      return;
    }
    for (const item of value.split(",")) {
      const index = item.indexOf("=");
      const key = index >= 0 ? item.slice(0, index).trim() : "";
      const headerValue = index >= 0 ? item.slice(index + 1).trim() : "";
      if (!key || !headerValue) {
        context.addIssue({
          code: "custom",
          message: `${variableName} must use comma-separated key=value pairs`,
        });
        return;
      }
    }
  });
const otelLogsExporterString = z.enum(["none", "otlp"]).default("none");

const fidesEnvObjectSchema = z.object({
  FIDES_RUNTIME_ENV: defaultedTrimmedString("local"),
  FIDES_OTP_ADAPTER: z.enum(["real", "mock", "disabled"]).default("mock"),
  FIDES_BFF_BASE_URL: requiredTrimmedString,
  FIDES_BROWSER_TRACING_ENDPOINT: optionalTrimmedString,
  FIDES_BROWSER_TRACING_HEADERS: headersString("FIDES_BROWSER_TRACING_HEADERS"),
  OTEL_LOGS_EXPORTER: otelLogsExporterString,
  OTEL_EXPORTER_OTLP_LOGS_ENDPOINT: optionalTrimmedString,
  OTEL_EXPORTER_OTLP_LOGS_HEADERS: headersString("OTEL_EXPORTER_OTLP_LOGS_HEADERS"),
  OTEL_SERVICE_NAME: defaultedTrimmedString("fides-web"),
});

export const fidesEnvSchema = fidesEnvObjectSchema.superRefine((value, context) => {
  if (value.OTEL_LOGS_EXPORTER === "otlp" && !value.OTEL_EXPORTER_OTLP_LOGS_ENDPOINT) {
    context.addIssue({
      code: "custom",
      path: ["OTEL_EXPORTER_OTLP_LOGS_ENDPOINT"],
      message: "OTEL_EXPORTER_OTLP_LOGS_ENDPOINT is required when OTEL_LOGS_EXPORTER=otlp",
    });
  }
});

export const clientEnvSchema = z.object({}).strict();

const legacyPublicEnvKeys = [
  "NEXT_PUBLIC_FIDES_OTP_ADAPTER",
  "NEXT_PUBLIC_FIDES_BFF_BASE_URL",
  "NEXT_PUBLIC_OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
  "NEXT_PUBLIC_OTEL_EXPORTER_OTLP_TRACES_HEADERS",
] as const;

export const smokeEnvSchema = z
  .object({
    LEN43_REAL_BFF_SMOKE: z.enum(["0", "1"]).default("0"),
    LEN43_FIDES_BFF_BASE_URL: optionalTrimmedString,
    LEN43_SMOKE_PHONE: defaultedTrimmedString("91989999"),
  })
  .superRefine((value, context) => {
    if (value.LEN43_REAL_BFF_SMOKE === "1" && !value.LEN43_FIDES_BFF_BASE_URL) {
      context.addIssue({
        code: "custom",
        path: ["LEN43_FIDES_BFF_BASE_URL"],
        message: "LEN43_FIDES_BFF_BASE_URL is required when LEN43_REAL_BFF_SMOKE=1",
      });
    }
  })
  .transform((value) => ({
    realBffSmoke: value.LEN43_REAL_BFF_SMOKE === "1",
    fidesBffBaseUrl: value.LEN43_FIDES_BFF_BASE_URL,
    smokePhone: value.LEN43_SMOKE_PHONE,
  }));

export type FidesEnv = z.infer<typeof fidesEnvSchema>;
export type SmokeEnv = z.infer<typeof smokeEnvSchema>;

export function getFidesEnv(): FidesEnv {
  validateNoLegacyPublicEnv();
  return fidesEnvSchema.parse(process.env);
}

export function getRuntimeEnvironmentFromEnv(): string {
  const parsed = fidesEnvObjectSchema.shape.FIDES_RUNTIME_ENV.safeParse(
    process.env.FIDES_RUNTIME_ENV,
  );
  return parsed.success ? parsed.data : "local";
}

export function getSmokeEnv(): SmokeEnv {
  return smokeEnvSchema.parse(process.env);
}

export function validateNoLegacyPublicEnv() {
  const present = legacyPublicEnvKeys.filter((name) => process.env[name]);
  if (present.length > 0) {
    throw new Error(`Legacy public runtime variables are not supported: ${present.join(", ")}`);
  }
}
