import type { Metadata } from 'next';
import './globals.css';
import { inter, mono } from '@/shared/lib/fonts';
import { cn } from '@/shared/lib/cn';

export const metadata: Metadata = {
  title: "JeekLee's playground",
  description:
    "A personal workshop, open to read. Documents, chat, and system status as each milestone ships.",
  // Favicon comes from the file-based `src/app/icon.tsx` convention —
  // Next 14 auto-injects the `<link rel="icon">` tag at build time.
};

/**
 * Root layout — owns only the typography / theme wiring. The
 * sidebar+topbar shell is opt-in per route: `/` mounts it (see
 * `(shell)/layout.tsx`), `/login` and `/401` skip it.
 */
export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={cn(inter.variable, mono.variable)}>
      <body className="min-h-screen bg-bg text-text antialiased">{children}</body>
    </html>
  );
}
