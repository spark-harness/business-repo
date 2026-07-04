import { getFidesEnv } from "@/config/env";

export function getBffProxyBaseUrl(): string {
  return getFidesEnv().FIDES_BFF_BASE_URL.replace(/\/+$/, "");
}
