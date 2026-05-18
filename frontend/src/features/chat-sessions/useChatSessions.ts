'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  createSession,
  deleteSession,
  listSessions,
  renameSession,
} from '@/shared/api/chat';
import type { ChatSession } from '@/entities/chat';

/**
 * `useChatSessions` — owns the caller's session list, sorted by
 * `updatedAt DESC` (matching the backend ORDER BY per spec §5.3).
 *
 * The hook intentionally avoids React Query / SWR — the playground
 * does not (yet) carry either dep, and the session list is one-shot per
 * page mount with explicit refresh after each CRUD. Pinning a fetch
 * library can wait until we have multiple consumers.
 *
 * `initialSessions` lets the SSR pass hydrate the list so the first
 * paint already shows tabs.
 */

export interface UseChatSessionsApi {
  sessions: ChatSession[];
  /** True until the first fetch completes (false when SSR-hydrated). */
  isLoading: boolean;
  /** Non-null when the last operation failed. */
  error: string | null;
  /** Refetch from the server — wipes optimistic state. */
  refresh: () => Promise<void>;
  /** Create a fresh session. Returns the new session id on success. */
  create: () => Promise<string | null>;
  /** Rename — optimistic in the local list. */
  rename: (id: string, title: string) => Promise<void>;
  /** Delete — optimistic removal. */
  remove: (id: string) => Promise<void>;
  /**
   * Locally promote a session to the head of the list (`updatedAt =
   * now`). Used after a successful chat turn so the tab strip's max-7
   * window reflects the latest activity without waiting for a refetch.
   */
  bumpUpdatedAt: (id: string) => void;
}

export function useChatSessions(initialSessions: ChatSession[] = []): UseChatSessionsApi {
  const [sessions, setSessions] = useState<ChatSession[]>(initialSessions);
  const [isLoading, setIsLoading] = useState(initialSessions.length === 0);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    setIsLoading(true);
    const res = await listSessions();
    setIsLoading(false);
    if (res.kind === 'ok') {
      const sorted = [...res.value.sessions].sort(
        (a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime(),
      );
      setSessions(sorted);
      setError(null);
    } else if (res.kind === 'unauthorized') {
      setError('unauthorized');
    } else {
      setError('Could not load chats');
    }
  }, []);

  // Fetch on mount when SSR didn't hydrate us.
  useEffect(() => {
    if (initialSessions.length === 0) {
      void refresh();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const create = useCallback(async (): Promise<string | null> => {
    const res = await createSession();
    if (res.kind !== 'ok') {
      setError('Could not create a new chat');
      return null;
    }
    const next: ChatSession = {
      id: res.value.sessionId,
      title: res.value.title,
      updatedAt: new Date().toISOString(),
      messageCount: 0,
    };
    setSessions((prev) => [next, ...prev]);
    setError(null);
    return next.id;
  }, []);

  const rename = useCallback(async (id: string, title: string) => {
    // Optimistic
    const trimmed = title.trim();
    if (!trimmed) return;
    const prevTitle = sessions.find((s) => s.id === id)?.title;
    setSessions((prev) => prev.map((s) => (s.id === id ? { ...s, title: trimmed } : s)));
    const res = await renameSession(id, trimmed);
    if (res.kind !== 'ok') {
      // Rollback
      if (prevTitle !== undefined) {
        setSessions((prev) =>
          prev.map((s) => (s.id === id ? { ...s, title: prevTitle } : s)),
        );
      }
      setError('Could not rename chat');
    }
  }, [sessions]);

  const remove = useCallback(async (id: string) => {
    const prev = sessions;
    setSessions((cur) => cur.filter((s) => s.id !== id));
    const res = await deleteSession(id);
    if (res.kind !== 'ok' && res.kind !== 'not-found') {
      // Rollback (treat 404 as success — idempotent delete)
      setSessions(prev);
      setError('Could not delete chat');
    }
  }, [sessions]);

  const bumpUpdatedAt = useCallback((id: string) => {
    setSessions((prev) => {
      const idx = prev.findIndex((s) => s.id === id);
      if (idx < 0) return prev;
      const target = prev[idx]!;
      const updated: ChatSession = { ...target, updatedAt: new Date().toISOString() };
      const rest = prev.filter((_, i) => i !== idx);
      return [updated, ...rest];
    });
  }, []);

  return {
    sessions,
    isLoading,
    error,
    refresh,
    create,
    rename,
    remove,
    bumpUpdatedAt,
  };
}
