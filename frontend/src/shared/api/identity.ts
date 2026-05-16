import { headers } from 'next/headers';

/**
 * Identity API client. Routed through the gateway per ADR-07:
 *   /api/users/me   →  identity-api  /me
 *
 * The browser always talks to the gateway as the same origin, so on the
 * client side a relative path works. On the server side (root layout's
 * SSR render) we forward inbound cookies so the gateway sees the user's
 * session.
 *
 * The wire DTO lives here in `shared/api/` (the source of truth for the
 * HTTP contract). The `entities/user` slice re-exports it as the domain
 * `User` type — keeping FSD layering clean (`shared` does not depend on
 * `entities`).
 */
export interface UserDto {
  /** Internal `identity.users.id` UUID — matches `X-User-Id`. */
  id: string;
  /** Immutable OIDC `sub` claim from Google. */
  googleSub: string;
  /** Email from the OIDC `email` claim. */
  email: string;
  /** Display name from Google profile. */
  displayName: string;
  /** Avatar URL — null when Google did not return a picture. */
  avatarUrl: string | null;
}

const ME_PATH = '/api/users/me';

/**
 * Inside the Next.js server runtime, hit the gateway via the
 * compose-internal hostname (`gateway:18080`) so we don't bounce out of
 * the docker network. Outside compose this still works for local dev
 * because `NEXT_PUBLIC_GATEWAY_URL` is honored when set.
 */
function gatewayBaseUrl(): string {
  return process.env.GATEWAY_INTERNAL_URL ?? 'http://gateway:18080';
}

export type MeResult =
  | { kind: 'authenticated'; user: UserDto }
  | { kind: 'anonymous' }
  | { kind: 'error'; status: number };

/**
 * Server-side `/me` fetch. Forwards the inbound cookie header so the
 * gateway can resolve `PLAYGROUND_SESSION`.
 *
 * - 200 → `authenticated` with the typed User.
 * - 401 → `anonymous` (public render).
 * - else → `error` (log + render public shell, per dispatch spec).
 */
export async function fetchMeServerSide(): Promise<MeResult> {
  const inboundCookie = headers().get('cookie') ?? '';
  try {
    const res = await fetch(`${gatewayBaseUrl()}${ME_PATH}`, {
      method: 'GET',
      headers: {
        accept: 'application/json',
        cookie: inboundCookie,
      },
      cache: 'no-store',
    });

    if (res.status === 200) {
      const body = (await res.json()) as UserDto;
      return { kind: 'authenticated', user: body };
    }
    if (res.status === 401) {
      return { kind: 'anonymous' };
    }
    return { kind: 'error', status: res.status };
  } catch {
    // Network / DNS / refused — treat as transient and fall through to
    // the public shell. Caller logs.
    return { kind: 'error', status: 0 };
  }
}
