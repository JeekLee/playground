/**
 * `Document` entity — domain types matching M2 docs BC spec v5 §6.4.
 * The wire DTOs live in `shared/api/docs.ts`; this slice re-exports them
 * as the domain `Document` family so FSD layering stays clean
 * (`entities` depends on `shared`, never the other way around).
 *
 * S1 scope (single-author CRUD) consumes:
 *   - `MyDocListItem` for `/docs/mine`.
 *   - `DocDetail` for `/docs/{id}`.
 *   - `CreateDocRequest` / `PatchDocRequest` for `/docs/new` + editor save.
 *
 * The community-feed / search / like / view shapes are still defined in the
 * wire client below for forward compatibility, but no S1 view consumes
 * them yet.
 */

import type {
  AuthorDto,
  CreateDocRequestDto,
  DocDetailDto,
  DocListItemDto,
  DocVisibility,
  MyDocListItemDto,
  PatchDocRequestDto,
} from '@/shared/api/docs';

export type Visibility = DocVisibility;
export type Author = AuthorDto;
export type Document = DocDetailDto;
export type DocumentListItem = DocListItemDto;
export type MyDocumentListItem = MyDocListItemDto;
export type CreateDocumentRequest = CreateDocRequestDto;
export type PatchDocumentRequest = PatchDocRequestDto;

/**
 * Format an ISO-8601 timestamp as `Apr 12, 2026` for meta rows.
 * Pure formatter; safe on the server.
 */
export function formatDate(iso: string): string {
  const date = new Date(iso);
  return date.toLocaleDateString('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  });
}

/**
 * Format an ISO-8601 timestamp as a coarse relative time
 * (`2h ago`, `3d ago`, `2w ago`, `Jan 4`). Browser-friendly; same logic
 * runs on the server during SSR.
 */
export function formatRelative(iso: string, now: Date = new Date()): string {
  const date = new Date(iso);
  const seconds = Math.max(0, Math.round((now.getTime() - date.getTime()) / 1000));
  if (seconds < 60) return 'just now';
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.round(hours / 24);
  if (days < 7) return `${days}d ago`;
  const weeks = Math.round(days / 7);
  if (weeks < 5) return `${weeks}w ago`;
  return formatDate(iso);
}
