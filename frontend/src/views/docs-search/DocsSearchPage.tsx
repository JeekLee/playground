'use client';

import { useEffect, useRef, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { AlertTriangle, Search as SearchIcon } from 'lucide-react';
import { cn } from '@/shared/lib/cn';
import { SearchHitCard, SearchHitCardSkeleton } from '@/widgets/search-hit-card';
import { searchDocs } from '@/shared/api/docs';
import type { DocSearchScope, SearchHit } from '@/entities/document';

/**
 * DocsSearchPage — `/docs/search` full-page search results.
 *
 * Per design doc M2-docs.md §"Search results":
 *  - sticky large search input + 2-segment scope toggle (Mine/Public)
 *  - result count line
 *  - hit cards (see {@link SearchHitCard})
 *  - state copy:
 *      empty q     → "Start typing to search your documents."
 *      no results  → "No matches for \"<q>\"" + suggestion
 *      503         → "Search is unavailable right now. Other features still work."
 *      loading     → 4 hit skeletons
 *
 * Behavior pinned by the design doc:
 *  - Typing requeries with a 300ms debounce (dispatch spec).
 *  - URL updates with `?q=…&scope=…` for share-ability (we use
 *    `router.replace` so history isn't polluted).
 *  - Scope toggle swaps the result set live.
 *  - Server-side initial render uses the same component shape via the
 *    `initialQuery` / `initialResults` props.
 */

export interface DocsSearchPageProps {
  initialQuery: string;
  initialScope: DocSearchScope;
  /** When the server-side fetch succeeded, the hits are preloaded here. */
  initialResults: SearchHit[];
  /** True when the server-side fetch returned 503. */
  initialUnavailable?: boolean;
  /** Authenticated callers default scope to Mine; anonymous default to Public. */
  defaultScope: DocSearchScope;
}

const DEBOUNCE_MS = 300;

export function DocsSearchPage({
  initialQuery,
  initialScope,
  initialResults,
  initialUnavailable,
  defaultScope,
}: DocsSearchPageProps) {
  const router = useRouter();
  const searchParams = useSearchParams();
  const [query, setQuery] = useState(initialQuery);
  const [scope, setScope] = useState<DocSearchScope>(initialScope);
  const [hits, setHits] = useState<SearchHit[]>(initialResults);
  const [loading, setLoading] = useState(false);
  const [unavailable, setUnavailable] = useState(Boolean(initialUnavailable));
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  // Track which request is "live" so a slow first response can't overwrite
  // a faster later one.
  const requestSeq = useRef(0);
  // Skip the first effect run when the SSR'd values already match the
  // initial query/scope (avoid a redundant client requery).
  const hydrated = useRef(false);

  // Sync state when the user navigates with the back button (URL changes).
  useEffect(() => {
    const urlQ = searchParams.get('q') ?? '';
    const urlScope = (searchParams.get('scope') as DocSearchScope | null) ?? defaultScope;
    if (urlQ !== query) setQuery(urlQ);
    if (urlScope !== scope) setScope(urlScope);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams]);

  // Debounced search on query/scope change.
  useEffect(() => {
    if (!hydrated.current) {
      hydrated.current = true;
      return;
    }

    // Empty query → drop results, no network call.
    if (query.trim().length === 0) {
      setHits([]);
      setUnavailable(false);
      setErrorMsg(null);
      const params = new URLSearchParams();
      params.set('scope', scope);
      router.replace(`/docs/search?${params.toString()}`);
      return;
    }

    const handle = window.setTimeout(async () => {
      const mySeq = ++requestSeq.current;
      setLoading(true);
      setErrorMsg(null);
      const result = await searchDocs({ q: query, scope });
      if (mySeq !== requestSeq.current) return; // a newer request finished first
      setLoading(false);
      if (result.kind === 'ok') {
        setHits(result.value.items);
        setUnavailable(false);
      } else if (result.kind === 'service-unavailable') {
        setUnavailable(true);
        setHits([]);
      } else if (result.kind === 'unauthorized') {
        // Mine scope hit by anonymous — flip to public transparently.
        setScope('public');
      } else if (result.kind === 'rate-limited') {
        setErrorMsg('Slow down — search is rate-limited.');
      } else {
        setErrorMsg("Couldn't run that search. Try again.");
      }

      // Reflect the live query in the URL for share-ability.
      const params = new URLSearchParams();
      params.set('q', query);
      params.set('scope', scope);
      router.replace(`/docs/search?${params.toString()}`);
    }, DEBOUNCE_MS);

    return () => window.clearTimeout(handle);
  }, [query, scope, router]);

  const trimmedQ = query.trim();
  const hasResults = hits.length > 0;

  return (
    <div className="mx-auto flex w-full max-w-[820px] flex-col gap-lg px-[28px] py-[26px]">
      <div className="flex flex-col gap-sm">
        <p className="text-eyebrow text-accent">Find a document</p>
        <h1 className="text-h2 text-text">Search</h1>
      </div>

      <SearchBar
        value={query}
        onChange={setQuery}
        scope={scope}
        onScopeChange={setScope}
        loading={loading}
      />

      {/* Status / result count band */}
      <div className="text-small text-text-muted" aria-live="polite">
        {unavailable ? (
          <span aria-hidden="true" />
        ) : trimmedQ.length === 0 ? (
          <span>Start typing to search.</span>
        ) : loading ? (
          <span>Searching…</span>
        ) : (
          <span>
            {hits.length} {hits.length === 1 ? 'result' : 'results'} for{' '}
            <span className="text-text">&ldquo;{trimmedQ}&rdquo;</span>
          </span>
        )}
      </div>

      {errorMsg && (
        <ErrorBanner
          icon={<AlertTriangle size={14} aria-hidden="true" />}
          message={errorMsg}
        />
      )}

      {unavailable ? (
        <UnavailableState onRetry={() => setQuery((q) => q)} />
      ) : loading ? (
        <SkeletonStack />
      ) : trimmedQ.length === 0 ? (
        <EmptyQueryState />
      ) : !hasResults ? (
        <NoResultsState query={trimmedQ} />
      ) : (
        <div className="flex flex-col gap-md">
          {hits.map((hit) => (
            <SearchHitCard key={hit.documentId} hit={hit} />
          ))}
        </div>
      )}
    </div>
  );
}

interface SearchBarProps {
  value: string;
  onChange: (v: string) => void;
  scope: DocSearchScope;
  onScopeChange: (s: DocSearchScope) => void;
  loading: boolean;
}

function SearchBar({ value, onChange, scope, onScopeChange, loading }: SearchBarProps) {
  return (
    <div className="sticky top-0 z-10 -mx-[28px] flex flex-wrap items-center gap-md border-b border-border bg-bg px-[28px] py-md">
      <div className="relative flex-1 min-w-[260px]">
        <SearchIcon
          size={14}
          aria-hidden="true"
          className="pointer-events-none absolute left-md top-1/2 -translate-y-1/2 text-text-muted"
        />
        <input
          type="search"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder="Search…"
          aria-label="Search documents"
          autoComplete="off"
          autoFocus
          className={cn(
            'h-[40px] w-full rounded-pill border border-border-strong bg-surface pl-[36px] pr-[42px] text-body text-text placeholder:text-text-subtle',
            'focus:border-accent focus:outline-none focus:ring-2 focus:ring-accent-soft',
          )}
        />
        {loading && (
          <span
            aria-hidden="true"
            className="absolute right-md top-1/2 inline-block h-[12px] w-[12px] -translate-y-1/2 animate-spin rounded-pill border-2 border-border-strong border-t-accent"
          />
        )}
      </div>
      <ScopeToggle scope={scope} onScopeChange={onScopeChange} />
    </div>
  );
}

function ScopeToggle({
  scope,
  onScopeChange,
}: {
  scope: DocSearchScope;
  onScopeChange: (s: DocSearchScope) => void;
}) {
  return (
    <div
      role="tablist"
      aria-label="Search scope"
      className="inline-flex items-center gap-[2px] rounded-pill border border-border bg-surface-soft p-[2px]"
    >
      <ScopeButton
        active={scope === 'mine'}
        onClick={() => onScopeChange('mine')}
        label="Mine"
      />
      <ScopeButton
        active={scope === 'public'}
        onClick={() => onScopeChange('public')}
        label="Public"
      />
    </div>
  );
}

function ScopeButton({
  active,
  onClick,
  label,
}: {
  active: boolean;
  onClick: () => void;
  label: string;
}) {
  return (
    <button
      type="button"
      role="tab"
      aria-selected={active}
      onClick={onClick}
      className={cn(
        'rounded-pill px-[14px] py-[5px] text-small font-medium transition-colors duration-[140ms]',
        active ? 'bg-surface text-accent shadow-card' : 'text-text-muted hover:text-text',
      )}
    >
      {label}
    </button>
  );
}

function SkeletonStack() {
  return (
    <div className="flex flex-col gap-md">
      {Array.from({ length: 4 }).map((_, i) => (
        <SearchHitCardSkeleton key={i} />
      ))}
    </div>
  );
}

function EmptyQueryState() {
  return (
    <div className="rounded-md border border-dashed border-border-strong bg-surface px-md py-xl text-center">
      <p className="text-small text-text-muted">
        Start typing — search is keyboard-fastest at{' '}
        <kbd className="rounded-sm bg-surface-soft px-[6px] py-[1px] font-mono text-[11px] text-text-muted">
          ⌘K
        </kbd>{' '}
        too.
      </p>
    </div>
  );
}

function NoResultsState({ query }: { query: string }) {
  return (
    <div className="rounded-md border border-border bg-surface px-md py-xl text-center shadow-card">
      <p className="text-h3 text-text">No matches for &ldquo;{query}&rdquo;</p>
      <p className="mt-xs text-small text-text-muted">
        Try a broader keyword or switch scope.
      </p>
    </div>
  );
}

function UnavailableState({ onRetry }: { onRetry: () => void }) {
  return (
    <div
      role="alert"
      className="flex flex-col items-start gap-sm rounded-md border border-danger bg-danger-soft px-md py-md text-small"
    >
      <p className="font-medium text-danger">Search is unavailable right now.</p>
      <p className="text-text-muted">Other features still work — try again in a moment.</p>
      <button
        type="button"
        onClick={onRetry}
        className="text-small font-medium text-accent hover:text-accent-hover"
      >
        Retry &rarr;
      </button>
    </div>
  );
}

function ErrorBanner({
  icon,
  message,
}: {
  icon: React.ReactNode;
  message: string;
}) {
  return (
    <div
      role="status"
      className="flex items-center gap-sm rounded-md border border-warning bg-warning-soft px-md py-sm text-small text-warning"
    >
      {icon}
      <span>{message}</span>
    </div>
  );
}

