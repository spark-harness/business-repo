export default function Home() {
  return (
    <div className="flex min-h-screen bg-neutral-950 text-neutral-50">
      <main className="mx-auto flex w-full max-w-5xl flex-col justify-center gap-10 px-6 py-16 sm:px-10">
        <div className="flex items-center gap-3">
          <div className="h-3 w-3 rounded-full bg-emerald-400" />
          <span className="text-sm font-medium uppercase tracking-[0.16em] text-emerald-200">
            Aegis
          </span>
        </div>
        <section className="max-w-3xl">
          <h1 className="text-5xl font-semibold tracking-normal text-white sm:text-7xl">
            Aegis
          </h1>
          <p className="mt-6 max-w-2xl text-lg leading-8 text-neutral-300">
            Frontend shell for Spark business workflows.
          </p>
        </section>
      </main>
    </div>
  );
}
