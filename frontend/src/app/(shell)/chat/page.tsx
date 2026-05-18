import { headers } from 'next/headers';
import { redirect } from 'next/navigation';
import { loadMe } from '@/features/me';
import {
  fetchSessionMessagesServerSide,
  fetchSessionsServerSide,
} from '@/shared/api/chat.server';
import { ChatPage } from '@/views/chat';

/**
 * `/chat` — authenticated-only route per ADR-09 §G.4 amendment + ADR-14
 * §G.4. Anonymous visitors are redirected to `/login?next=/chat`.
 *
 * SSR contract (per design doc §1.5 + §2.1):
 *  - Fetch the caller's session list (sorted `updatedAt DESC`).
 *  - If at least one session exists, pre-load the most-recent session's
 *    message history so the first paint already shows the loaded
 *    conversation (frame `54:233`).
 *  - If the list is empty, render the empty-state hero (frame `54:8`).
 *  - Pass `Accept-Language` through so the empty-state suggestion chips
 *    can pick KO vs EN per ADR-14 §12.
 *
 * `searchParams.sessionId` deep-link: jumping straight to a specific
 * session is supported when the id is in the caller's list; otherwise we
 * fall back to the most-recent session.
 */

export const dynamic = 'force-dynamic';

type PageProps = {
  searchParams: { sessionId?: string };
};

export default async function ChatRoute({ searchParams }: PageProps) {
  const me = await loadMe();
  if (me.kind === 'anonymous') {
    redirect('/login?next=' + encodeURIComponent('/chat'));
  }

  const sessionsResult = await fetchSessionsServerSide();
  if (sessionsResult.kind === 'unauthorized') {
    redirect('/login?next=' + encodeURIComponent('/chat'));
  }

  const sessions = sessionsResult.kind === 'ok' ? sessionsResult.value.sessions : [];
  const sortedSessions = [...sessions].sort(
    (a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime(),
  );

  // Pick the active session: explicit param wins, else the most-recent.
  let activeId: string | null = null;
  if (searchParams.sessionId && sortedSessions.some((s) => s.id === searchParams.sessionId)) {
    activeId = searchParams.sessionId;
  } else if (sortedSessions[0]) {
    activeId = sortedSessions[0].id;
  }

  let initialMessages: import('@/entities/chat').Message[] = [];
  if (activeId) {
    const msgRes = await fetchSessionMessagesServerSide(activeId);
    if (msgRes.kind === 'ok') {
      initialMessages = msgRes.value.messages;
    }
  }

  const acceptLanguage = headers().get('accept-language') ?? undefined;
  const locale = acceptLanguage?.split(',')[0]?.trim();

  return (
    <ChatPage
      initialSessions={sortedSessions}
      initialActiveSessionId={activeId}
      initialMessages={initialMessages}
      locale={locale}
    />
  );
}

export const metadata = {
  // Per design doc open-question #6: keep the OG title generic; no
  // session titles or content (PII concern).
  title: "Chat · JeekLee's playground",
  description: 'Ask anything about your playground corpus. Answers cite the source chunk.',
};
