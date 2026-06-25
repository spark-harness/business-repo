import { MobileVerificationScreen } from "@/presentation/mobile-verification/mobile-verification-screen";
import { ObservabilityBootstrap } from "@/api/observability-bootstrap";

export default function Home() {
  return (
    <>
      <ObservabilityBootstrap />
      <MobileVerificationScreen />
    </>
  );
}
