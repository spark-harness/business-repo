import { FidesApplicationClient } from "@/api/fides-application-client";
import { ObservabilityBootstrap } from "@/api/observability-bootstrap";
import { getPublicRuntimeConfig } from "@/api/runtime-config/get-public-runtime-config";

export const dynamic = "force-dynamic";

export default async function Home() {
  const runtimeConfig = await getPublicRuntimeConfig();

  return (
    <>
      <ObservabilityBootstrap config={runtimeConfig} />
      <FidesApplicationClient runtimeConfig={runtimeConfig} />
    </>
  );
}
