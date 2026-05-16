import { Brand } from '@/shared/ui/brand';
import { Chip } from '@/shared/ui/chip';
import { Button } from '@/shared/ui/button';
import { SignInButton } from '@/features/sign-in';

/**
 * Unauthorized composition — design context's `/401`. No sidebar, no
 * topbar. Brand row floated top-left, single centered card with the
 * 401 chip, headline, body, button row (primary `Continue with Google`
 * + secondary `Go home`), and footnote.
 */

export function UnauthorizedPage() {
  return (
    <div className="flex min-h-screen flex-col bg-bg">
      <div className="px-lg pt-lg">
        <Brand />
      </div>

      <main className="flex flex-1 items-center justify-center px-md py-xl">
        <section
          aria-labelledby="unauthorized-headline"
          className="flex w-full max-w-[560px] flex-col gap-lg rounded-lg border border-border bg-surface p-xl shadow-card"
        >
          <Chip variant="danger" dot>
            401 · UNAUTHORIZED
          </Chip>
          <div className="flex flex-col gap-sm">
            <h1 id="unauthorized-headline" className="text-h1 text-text">
              You need to sign in for this one
            </h1>
            <p className="text-body text-text-muted">
              This page needs an account — writing documents, private chats, or your own
              documents all require sign-in. Reading the site (home, documents, public chat,
              system status) doesn&rsquo;t.
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-sm">
            <SignInButton label="Continue with Google" />
            <Button href="/" variant="secondary">
              Go home
            </Button>
          </div>
          <p className="text-small text-text-subtle">
            After signing in we&rsquo;ll bring you back to the page you tried to open.
          </p>
        </section>
      </main>
    </div>
  );
}
