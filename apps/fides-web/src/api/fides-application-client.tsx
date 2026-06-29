"use client";

import dynamic from "next/dynamic";

import type { PublicRuntimeConfig } from "@/api/runtime-config/public-runtime-config";

const ClientOnlyFidesApplication = dynamic(
  () => import("@/api/fides-application").then((module) => module.FidesApplication),
  { ssr: false },
);

export function FidesApplicationClient({ runtimeConfig }: { runtimeConfig: PublicRuntimeConfig }) {
  return <ClientOnlyFidesApplication runtimeConfig={runtimeConfig} />;
}
