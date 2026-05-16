'use client';

import { useState } from 'react';
import { Button } from '@/shared/ui/button';

/**
 * `Sign out` — POSTs `/logout` with the CSRF token Spring Security
 * exposes via the `XSRF-TOKEN` cookie (ADR-07 §CSRF). On success the
 * browser is redirected to `/` (Public Home per PRD).
 *
 * Client component because we need to read `document.cookie` and call
 * `window.location.assign`. The boundary stays tight: a single button.
 */

function getXsrfToken(): string | null {
  if (typeof document === 'undefined') return null;
  const match = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]+)/);
  return match && match[1] ? decodeURIComponent(match[1]) : null;
}

export function SignOutButton() {
  const [pending, setPending] = useState(false);

  async function handleClick() {
    if (pending) return;
    setPending(true);
    try {
      const token = getXsrfToken();
      await fetch('/logout', {
        method: 'POST',
        credentials: 'same-origin',
        headers: token ? { 'X-XSRF-TOKEN': token } : undefined,
      });
    } catch {
      // Even if the network call hiccups, fall through to the home
      // redirect so the user sees the public shell.
    } finally {
      window.location.assign('/');
    }
  }

  return (
    <Button
      variant="ghost"
      onClick={handleClick}
      disabled={pending}
      aria-label="Sign out"
    >
      {pending ? 'Signing out…' : 'Sign out'}
    </Button>
  );
}
