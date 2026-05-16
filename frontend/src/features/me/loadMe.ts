import { fetchMeServerSide } from '@/shared/api/identity';
import type { MeResult } from '@/shared/api/identity';

/**
 * Feature wrapper around the typed `GET /me` server-side fetch. Adds a
 * single-line log on the error path so a transient identity outage shows
 * up in the SSR logs without crashing the public render.
 */
export async function loadMe(): Promise<MeResult> {
  const result = await fetchMeServerSide();
  if (result.kind === 'error') {
    // eslint-disable-next-line no-console
    console.warn(`[me] non-401 response from gateway: status=${result.status}`);
  }
  return result;
}

export type { MeResult } from '@/shared/api/identity';
