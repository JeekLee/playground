/**
 * Editor skeleton — server-renderable placeholder shown while the
 * dynamic-imported BlockNote bundle loads. Matches the editor's outer
 * dimensions (max-width 720px, 40px vertical padding) to avoid layout
 * shift per ADR-12 §3 "MD-roundtrip adapter / SSR strategy".
 */
export function EditorSkeleton() {
  return (
    <div className="mx-auto w-full max-w-[1100px] py-xl" aria-hidden="true">
      <div className="h-9 w-3/4 animate-pulse rounded-sm bg-surface-soft" />
      <div className="mt-lg flex flex-col gap-sm">
        <div className="h-4 w-full animate-pulse rounded-sm bg-surface-soft" />
        <div className="h-4 w-[90%] animate-pulse rounded-sm bg-surface-soft" />
        <div className="h-4 w-[60%] animate-pulse rounded-sm bg-surface-soft" />
      </div>
      <div className="mt-lg flex flex-col gap-sm">
        <div className="h-4 w-[80%] animate-pulse rounded-sm bg-surface-soft" />
        <div className="h-4 w-[70%] animate-pulse rounded-sm bg-surface-soft" />
      </div>
    </div>
  );
}
