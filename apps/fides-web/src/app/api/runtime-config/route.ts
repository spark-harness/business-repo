import { getRuntimeConfigResponse } from "@/api/runtime-config/runtime-config-route";

export const dynamic = "force-dynamic";

export async function GET() {
  return getRuntimeConfigResponse();
}
