import type { UserDto } from '@/shared/api/identity';

/**
 * `User` entity — the domain alias for the `UserDto` wire shape pinned in
 * `docs/prd/M1-identity.md` §외부 인터페이스 (the `GET /me` 200 body). The
 * wire type lives in `shared/api/identity.ts` so FSD layering stays clean
 * (`entities` depends on `shared`, never the other way around).
 *
 * `avatarUrl` is nullable because Google may not return a picture (PRD
 * §"Frontend treatment notes" pins null tolerance).
 */
export type User = UserDto;

/**
 * Compute initials for the khaki-fallback avatar (M1 design context's
 * Loading/Error state). Two characters max, uppercase.
 */
export function userInitials(user: Pick<User, 'displayName' | 'email'>): string {
  const source = user.displayName?.trim() || user.email?.trim() || '?';
  const parts = source.split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '?';
  if (parts.length === 1) {
    const first = parts[0]!;
    return first.slice(0, 2).toUpperCase();
  }
  const head = parts[0]![0] ?? '';
  const tail = parts[parts.length - 1]![0] ?? '';
  return (head + tail).toUpperCase();
}
