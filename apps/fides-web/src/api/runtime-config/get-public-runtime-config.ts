import {
  buildPublicRuntimeConfig,
  loadRuntimeConfig,
} from "@/infrastructure/runtime-config/runtime-config";
import type { PublicRuntimeConfig } from "./public-runtime-config";

export async function getPublicRuntimeConfig(): Promise<PublicRuntimeConfig> {
  return buildPublicRuntimeConfig(await loadRuntimeConfig());
}
