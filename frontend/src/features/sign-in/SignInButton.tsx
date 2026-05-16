import { Button } from '@/shared/ui/button';
import { GoogleGlyph } from './GoogleGlyph';

/**
 * `Sign in with Google` — server-rendered anchor to the gateway's
 * Spring Security entry point per ADR-07:
 *   GET /oauth2/authorization/google
 *
 * Anchor (no client JS) so the OAuth round-trip works even with JS
 * disabled. The gateway's `savedRequest` brings the user back to the
 * page they came from on success (ADR-10 §5).
 */

export interface SignInButtonProps {
  /** Optional label override — defaults to "Sign in with Google". */
  label?: string;
  /** Stretch to the parent width — used inside the Login card. */
  block?: boolean;
}

export function SignInButton({ label = 'Sign in with Google', block = false }: SignInButtonProps) {
  return (
    <Button
      href="/oauth2/authorization/google"
      variant="primary"
      className={block ? 'w-full' : undefined}
    >
      <GoogleGlyph />
      <span>{label}</span>
    </Button>
  );
}
