/**
 * Maps a backend container/service name (full {@code playground-*} prefix)
 * to a short display label suitable for dashboard cards.
 *
 * Backend wire shape (ADR-15 §G amendment 2026-05-21):
 *   - {@code playground-backend-identity-api}
 *   - {@code playground-postgres}
 *   - {@code playground-kafka-broker}
 *   - {@code spark-inference-gateway} (external — no prefix)
 *
 * UI display:
 *   - {@code identity-api} (drops {@code playground-backend-} prefix)
 *   - {@code postgres} (drops {@code playground-} prefix)
 *   - {@code kafka-broker}
 *   - {@code spark-inference-gateway} (unchanged — external)
 */

const PLAYGROUND_BACKEND_PREFIX = 'playground-backend-';
const PLAYGROUND_PREFIX = 'playground-';

/**
 * Strip the {@code playground-*} prefix for display. Names that don't match
 * (e.g., {@code spark-inference-gateway}) are returned unchanged.
 */
export function displayName(serviceOrContainer: string): string {
  if (serviceOrContainer.startsWith(PLAYGROUND_BACKEND_PREFIX)) {
    return serviceOrContainer.slice(PLAYGROUND_BACKEND_PREFIX.length);
  }
  if (serviceOrContainer.startsWith(PLAYGROUND_PREFIX)) {
    return serviceOrContainer.slice(PLAYGROUND_PREFIX.length);
  }
  return serviceOrContainer;
}

/**
 * Even shorter — used by space-constrained cells (e.g., the
 * service-health grid badge). Mostly identical to {@link displayName}
 * but also collapses {@code spark-inference-gateway} → {@code spark-gateway}.
 */
export function shortName(serviceOrContainer: string): string {
  if (serviceOrContainer === 'spark-inference-gateway') {
    return 'spark-gateway';
  }
  return displayName(serviceOrContainer);
}
