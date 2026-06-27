import { MobileVerificationScreen } from "@/presentation/mobile-verification/mobile-verification-screen";
import { ObservabilityBootstrap } from "@/api/observability-bootstrap";
import { getPublicRuntimeConfig } from "@/api/runtime-config/get-public-runtime-config";

export const dynamic = "force-dynamic";

export default async function Home() {
  const runtimeConfig = await getPublicRuntimeConfig();

  return (
    <>
      <ObservabilityBootstrap config={runtimeConfig} />
      <MobileVerificationScreen runtimeConfig={runtimeConfig} />
    </>
  );
}
