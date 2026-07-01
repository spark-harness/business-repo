export type OtpAdapterMode = "real" | "mock" | "disabled";

export type BrowserTracingRuntimeConfig = {
  endpoint?: string;
  environment?: string;
  headers: Record<string, string>;
};

export type PublicRuntimeConfig = {
  otpAdapter: OtpAdapterMode;
  bffBaseUrl: string;
  browserTracing: BrowserTracingRuntimeConfig;
};
