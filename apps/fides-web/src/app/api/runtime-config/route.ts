import { getRuntimeConfigResponse } from "@/api/runtime-config/runtime-config-route";

export const dynamic = "force-dynamic";

export async function GET(request: Request) {
  return getRuntimeConfigResponse(request);
}
