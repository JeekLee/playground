'use client';

import { useCallback, useState } from 'react';
import { Heart } from 'lucide-react';
import { cn } from '@/shared/lib/cn';
import { likeDocument, unlikeDocument } from '@/shared/api/docs';

/**
 * LikeButton — `/docs/{id}` engagement strip.
 *
 * Per design doc M2-docs.md §"Document (/docs/{id})" §Key elements:
 *   "The like button is outline `border.strong` + `font.small`/500 +
 *    `♥ likeCount` glyph; for anonymous viewers it renders disabled
 *    (`text.muted` fg) with a small `text.muted` tooltip-hint span next
 *    to it reading `Sign in to like` (spec §7.3 working default)."
 *
 * Authenticated behavior (per design doc §"Document" §Interactions and
 * M2 docs BC spec v5 §6.1, §10):
 *  - Optimistic: flip `liked` + bump/decrement the counter immediately.
 *  - On API failure: roll back the local state and surface an inline
 *    `danger` chip-toast for 3s.
 *  - Idempotency: the API treats POST + DELETE as idempotent (spec
 *    §10 like-idempotency), so repeat clicks while in flight are safe.
 *
 * Anonymous behavior:
 *  - Click does NOT call the API. Instead the button reveals an
 *    inline `Sign in to like` hint and routes the next intentional
 *    click to `/oauth2/authorization/google` with the current URL as
 *    the savedRequest (gateway handles the post-OAuth bounce per
 *    ADR-07).
 */

export interface LikeButtonProps {
  documentId: string;
  /** Caller's like-state at SSR time; `undefined` ≡ anonymous. */
  initialLikedByMe: boolean | undefined;
  initialLikeCount: number;
  /** Truthy when the gateway didn't attach an `X-User-Id`. */
  isAnonymous: boolean;
  className?: string;
}

export function LikeButton({
  documentId,
  initialLikedByMe,
  initialLikeCount,
  isAnonymous,
  className,
}: LikeButtonProps) {
  const [liked, setLiked] = useState<boolean>(Boolean(initialLikedByMe));
  const [count, setCount] = useState<number>(Math.max(0, initialLikeCount));
  const [pending, setPending] = useState(false);
  const [errorVisible, setErrorVisible] = useState(false);
  const [signInHint, setSignInHint] = useState(false);

  const showError = useCallback(() => {
    setErrorVisible(true);
    window.setTimeout(() => setErrorVisible(false), 3000);
  }, []);

  const onClick = useCallback(async () => {
    if (isAnonymous) {
      // First click reveals the hint; second click follows the OAuth
      // route (kept as a real `<a>` below so right-click + middle-
      // click work too).
      setSignInHint(true);
      return;
    }
    if (pending) return;

    // Optimistic mutation — capture rollback values BEFORE the local
    // setState so a `Promise.all` race doesn't leak stale numbers.
    const prevLiked = liked;
    const prevCount = count;
    const nextLiked = !prevLiked;
    const nextCount = nextLiked ? prevCount + 1 : Math.max(0, prevCount - 1);
    setLiked(nextLiked);
    setCount(nextCount);
    setPending(true);

    const result = nextLiked
      ? await likeDocument(documentId)
      : await unlikeDocument(documentId);
    setPending(false);

    if (result.kind === 'ok') return;

    // Roll back + surface a chip-toast.
    setLiked(prevLiked);
    setCount(prevCount);
    showError();
  }, [count, documentId, isAnonymous, liked, pending, showError]);

  if (isAnonymous) {
    // Render as an `<a>` so right-click / middle-click work and the
    // OAuth URL is discoverable to screen readers — but only AFTER the
    // first click reveals the hint, so casual hovers don't trigger a
    // sign-in dance for accidental clicks.
    const nextUrl =
      typeof window === 'undefined'
        ? `/docs/${documentId}`
        : window.location.pathname + window.location.search;
    return (
      <div className={cn('inline-flex items-center gap-sm', className)}>
        <button
          type="button"
          onClick={onClick}
          aria-label="Sign in to like this document"
          className={cn(
            'inline-flex items-center gap-xs rounded-pill border border-border bg-surface px-md py-[5px] text-small text-text-muted',
            'transition-colors duration-[140ms] hover:border-border-strong hover:text-text',
            'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent',
          )}
          title="Sign in to like"
        >
          <Heart size={13} aria-hidden="true" />
          <span className="font-mono text-[12px]">{count}</span>
        </button>
        {signInHint && (
          <a
            href={`/oauth2/authorization/google?savedRequest=${encodeURIComponent(nextUrl)}`}
            className="text-small font-medium text-accent transition-colors duration-[140ms] hover:text-accent-hover"
          >
            Sign in to like &rarr;
          </a>
        )}
      </div>
    );
  }

  return (
    <div className={cn('inline-flex items-center gap-sm', className)}>
      <button
        type="button"
        onClick={onClick}
        disabled={pending}
        aria-pressed={liked}
        aria-label={liked ? 'Unlike this document' : 'Like this document'}
        className={cn(
          'inline-flex items-center gap-xs rounded-pill px-md py-[5px] text-small font-medium',
          'transition-all duration-[140ms]',
          'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent',
          liked
            ? 'border border-accent bg-accent text-surface hover:bg-accent-hover hover:border-accent-hover'
            : 'border border-border-strong bg-surface text-text-muted hover:border-accent hover:text-accent',
          pending && 'opacity-70',
        )}
      >
        <Heart
          size={13}
          aria-hidden="true"
          className={cn('transition-transform duration-[140ms]', liked && 'scale-110')}
          fill={liked ? 'currentColor' : 'none'}
        />
        <span className="font-mono text-[12px]">{count}</span>
      </button>
      {errorVisible && (
        <span
          role="status"
          className="inline-flex items-center rounded-pill bg-danger-soft px-sm py-[2px] text-[11px] font-medium text-danger"
        >
          Couldn&rsquo;t update like
        </span>
      )}
    </div>
  );
}
