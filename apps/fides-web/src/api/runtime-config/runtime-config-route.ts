import { NextResponse } from "next/server";

import { getPublicRuntimeConfig } from "./get-public-runtime-config";

export async function getRuntimeConfigResponse() {
  return NextResponse.json(await getPublicRuntimeConfig(), {
    headers: {
      "Cache-Control": "no-store",
    },
  });
}
