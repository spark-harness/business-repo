import { loadRuntimeConfig } from "@/infrastructure/runtime-config/runtime-config";

export async function getBffProxyBaseUrl(): Promise<string> {
  const config = await loadRuntimeConfig();
  const baseUrl = config.internal.bffBaseUrl;
  if (!baseUrl) {
    throw new Error("FIDES_BFF_BASE_URL is required for /api/v1 proxy");
  }
  return baseUrl.replace(/\/+$/, "");
}
