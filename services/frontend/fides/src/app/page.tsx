export default function Home() {
  return (
    <main className="mx-auto flex w-full max-w-5xl flex-1 flex-col justify-center gap-6 px-6 py-16">
      <div className="flex items-center gap-3">
        <div className="h-3 w-3 rounded-full bg-emerald-400" />
        <span className="text-sm font-medium uppercase tracking-[0.16em] text-emerald-600 dark:text-emerald-300">
          Fides
        </span>
      </div>
      <h1 className="text-4xl font-semibold tracking-tight sm:text-6xl">Fides</h1>
      <p className="max-w-2xl text-lg leading-8 text-neutral-600 dark:text-neutral-300">
        Frontend application shell for Spark business workflows. Clean
        Architecture layers under <code>src/</code> are guarded statically by
        dependency-cruiser (<code>pnpm lint:deps</code>).
      </p>
    </main>
  );
}
