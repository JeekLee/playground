import { Brand } from '@/shared/ui/brand';
import { Chip } from '@/shared/ui/chip';
import { SignInButton } from '@/features/sign-in';

/**
 * Login composition — design context's `/login` (bare layout, no sidebar/topbar).
 * Header bar with brand row + "Not signed in" chip, centered card with the
 * Continue-with-Google CTA, below-card tip anchoring the public/auth split.
 */

export function LoginPage() {
  return (
    <div className="flex min-h-screen flex-col bg-bg">
      <header className="flex items-center justify-between border-b border-border bg-bg px-[26px] py-[14px]">
        <Brand />
        <Chip variant="neutral">Not signed in</Chip>
      </header>

      <main className="flex flex-1 flex-col items-center justify-center gap-md px-md py-xl">
        <section
          aria-labelledby="login-headline"
          className="flex w-full max-w-[440px] flex-col gap-lg rounded-lg border border-border bg-surface p-xl shadow-card"
        >
          <div className="flex flex-col gap-sm">
            <p className="text-eyebrow text-accent">JeekLee&rsquo;s playground</p>
            <h1 id="login-headline" className="text-h1 text-text">
              Sign in to continue
            </h1>
            <p className="text-body text-text-muted">
              Sign in lets you write documents, save chats, and see your own documents.
              Reading the site doesn&rsquo;t require an account.
            </p>
          </div>
          <SignInButton label="Continue with Google" block />
          <p className="text-small text-text-subtle">
            We only read your name, email, and avatar. A session cookie keeps you signed in
            for 8 hours.
          </p>
        </section>

        <p className="max-w-[440px] text-small text-text-subtle">
          Tip: hitting an authenticated page while logged out brings you here. Reading the
          site doesn&rsquo;t.
        </p>
      </main>
    </div>
  );
}
