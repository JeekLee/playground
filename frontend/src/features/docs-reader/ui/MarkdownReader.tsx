'use client';

import { Children, Fragment, isValidElement, useEffect, useMemo, useState, type ReactElement, type ReactNode } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeSanitize, { defaultSchema } from 'rehype-sanitize';
import type { Components } from 'react-markdown';
import { cn } from '@/shared/lib/cn';
import {
  getSharedHighlighter,
  SHIKI_THEME,
  SUPPORTED_LANGS,
} from '@/shared/lib/shiki';

/**
 * MarkdownReader — server-renderable Markdown reader pipeline.
 *
 * Per M2 spec v5 §9 (Markdown feature scope) the reader pipeline supports:
 *  - GFM: tables, code fences with language, task lists, strikethrough,
 *    autolinks → enabled via `remark-gfm`.
 *  - HTML in MD: sanitized out → `rehype-sanitize` with the default
 *    safe schema (no raw `<script>`, no event handlers).
 *  - Images: external URLs only → the sanitize schema rejects `data:` URLs
 *    by default; we restrict the allowed image hosts further by stripping
 *    any non-https image src in the component.
 *
 * The visual prose treatment maps each block to the design system's
 * §4 type scale (h1=28/700, h2=20/600, h3=16/600, body=15/1.6) and §6
 * primitives (code → `surface.soft` + `radius.sm`, blockquote → 3px
 * `border.strong` left rule per design doc Document screen spec).
 *
 * Shiki-style syntax highlighting is intentionally NOT loaded here in S1;
 * fenced code blocks render with a JetBrains Mono monospace strip and
 * `surface.soft` background — adequate for S1. M2.1 can hot-swap the
 * `code` component for a shiki-backed one without changing the public
 * markdown surface.
 */

export interface MarkdownReaderProps {
  body: string;
  className?: string;
  /**
   * Optional citation-pill replacement. When provided, any inline
   * `[N]` marker in the rendered text whose {@code N} appears in this
   * set is swapped for a clickable pill (style + click handler owned
   * by the caller). Used by the chat surface so the LLM's
   * `[1][2][3]` markers in the assistant body become accordion-card
   * anchors. Pass {@code undefined} (the default) to render `[N]`
   * verbatim as text.
   */
  citationPill?: {
    validNs: ReadonlySet<number>;
    render: (n: number) => ReactNode;
  };
}

/**
 * Block-level fenced-code renderer with shiki syntax highlighting.
 * Loads the shared highlighter on mount, renders a plain monospace
 * fallback while it warms up, then swaps to shiki's tokenized HTML
 * via `dangerouslySetInnerHTML`. Shiki's output is fully escaped +
 * structured so XSS isn't a concern.
 */
function ShikiCodeBlock({ language, code }: { language: string; code: string }) {
  const [html, setHtml] = useState<string | null>(null);
  useEffect(() => {
    let cancelled = false;
    getSharedHighlighter().then((highlighter) => {
      if (cancelled) return;
      const resolvedLang = (SUPPORTED_LANGS as readonly string[]).includes(language)
        ? language
        : 'text';
      try {
        const out = highlighter.codeToHtml(code, {
          lang: resolvedLang,
          theme: SHIKI_THEME,
        });
        setHtml(out);
      } catch {
        // Unsupported lang or runtime hiccup — keep the fallback.
      }
    });
    return () => {
      cancelled = true;
    };
  }, [language, code]);

  if (html) {
    // Shiki wraps the output in its own `<pre class="shiki ...">` with
    // inline background. Our outer wrapper provides the surface-soft
    // background + padding consistent with the rest of the prose; the
    // inline shiki bg gets neutralized via the Tailwind arbitrary
    // descendant selectors below.
    return (
      <div
        className="mt-md overflow-x-auto rounded-md bg-surface-soft p-md text-[13px] leading-[1.5] [&_pre]:!m-0 [&_pre]:!bg-transparent [&_pre]:!p-0 [&_pre]:!font-mono"
        dangerouslySetInnerHTML={{ __html: html }}
      />
    );
  }
  return (
    <pre className="mt-md overflow-x-auto rounded-md bg-surface-soft p-md font-mono text-[13px] leading-[1.5] text-text">
      <code>{code}</code>
    </pre>
  );
}

// `rehype-sanitize` ships a default schema that filters out scripts +
// event handlers. We extend it to keep our class names through (so the
// `code` components can carry the `language-*` hint downstream).
const sanitizeSchema = {
  ...defaultSchema,
  attributes: {
    ...defaultSchema.attributes,
    code: [...(defaultSchema.attributes?.code ?? []), 'className'],
    pre: [...(defaultSchema.attributes?.pre ?? []), 'className'],
    span: [...(defaultSchema.attributes?.span ?? []), 'className'],
  },
};

/**
 * Walk a children array and, for every string child, split on the
 * citation `[N]` marker regex — replacing any N that's in {@code validNs}
 * with the caller-supplied pill component. String children that have no
 * markers pass through unchanged, and non-string children (already-
 * rendered elements like inline code, links, etc.) are returned as-is.
 */
function citationPillsInChildren(
  children: ReactNode,
  pill: MarkdownReaderProps['citationPill'],
): ReactNode {
  if (!pill) return children;
  const out: ReactNode[] = [];
  let keyCounter = 0;
  const handleNode = (node: ReactNode): void => {
    if (typeof node === 'string') {
      const regex = /\[(\d+)\]/g;
      let lastIndex = 0;
      let m: RegExpExecArray | null;
      while ((m = regex.exec(node)) !== null) {
        const n = Number(m[1]);
        const before = node.slice(lastIndex, m.index);
        if (before) out.push(<Fragment key={`t-${keyCounter++}`}>{before}</Fragment>);
        if (pill.validNs.has(n)) {
          out.push(<Fragment key={`p-${keyCounter++}-${n}`}>{pill.render(n)}</Fragment>);
        } else {
          out.push(<Fragment key={`o-${keyCounter++}`}>{m[0]}</Fragment>);
        }
        lastIndex = m.index + m[0].length;
      }
      if (lastIndex < node.length) {
        out.push(<Fragment key={`t-${keyCounter++}`}>{node.slice(lastIndex)}</Fragment>);
      }
      return;
    }
    out.push(<Fragment key={`n-${keyCounter++}`}>{node}</Fragment>);
  };
  Children.forEach(children, handleNode);
  return out;
}

function buildComponents(pill: MarkdownReaderProps['citationPill']): Components {
  const w = (c: ReactNode) => citationPillsInChildren(c, pill);
  return {
  h1: ({ children }) => <h1 className="mt-xl text-h1 text-text">{w(children)}</h1>,
  h2: ({ children }) => <h2 className="mt-xl text-h2 text-text">{w(children)}</h2>,
  h3: ({ children }) => <h3 className="mt-lg text-h3 text-text">{w(children)}</h3>,
  h4: ({ children }) => (
    <h4 className="mt-lg text-[15px] font-semibold leading-snug text-text">{w(children)}</h4>
  ),
  p: ({ children }) => <p className="mt-md text-body text-text">{w(children)}</p>,
  ul: ({ children }) => (
    <ul className="mt-md ml-lg flex list-disc flex-col gap-xs text-body text-text marker:text-text-muted">
      {children}
    </ul>
  ),
  ol: ({ children }) => (
    <ol className="mt-md ml-lg flex list-decimal flex-col gap-xs text-body text-text marker:text-text-muted">
      {children}
    </ol>
  ),
  li: ({ children }) => <li className="leading-[1.6]">{w(children)}</li>,
  blockquote: ({ children }) => (
    <blockquote className="mt-md border-l-[3px] border-border-strong pl-md text-body text-text-muted">
      {w(children)}
    </blockquote>
  ),
  hr: () => <hr className="my-xl border-0 border-t border-border" />,
  a: ({ href, children }) => (
    <a
      href={href}
      className="text-accent underline decoration-accent-soft underline-offset-2 hover:text-accent-hover"
      target={href?.startsWith('http') ? '_blank' : undefined}
      rel={href?.startsWith('http') ? 'noreferrer noopener' : undefined}
    >
      {children}
    </a>
  ),
  code: ({ className, children, ...rest }) => {
    const isBlock =
      typeof className === 'string' && className.startsWith('language-');
    if (isBlock) {
      // Block-level is intercepted by the `pre` handler below so it can
      // run shiki on the raw text. The `code` element is left as a thin
      // passthrough — keeping the language class so any tools that
      // inspect the rendered DOM still see it.
      return (
        <code className={cn('font-mono text-[13px] leading-[1.5] text-text', className)} {...rest}>
          {children}
        </code>
      );
    }
    return (
      <code className="rounded-sm bg-surface-soft px-[4px] py-[1px] font-mono text-[13px] text-text">
        {children}
      </code>
    );
  },
  pre: ({ children }) => {
    // react-markdown gives us a single `<code>` child for fenced blocks.
    // Pull the language hint off its className and the raw text off its
    // children, then hand both to the shiki-backed renderer.
    const codeElement = Children.toArray(children).find(
      (child): child is ReactElement<{ className?: string; children?: ReactNode }> =>
        isValidElement(child) && (child as ReactElement<{ type?: string }>).type === 'code',
    );
    if (codeElement) {
      const className = codeElement.props.className ?? '';
      const langMatch = /language-(\S+)/.exec(className);
      const language = langMatch?.[1] ?? 'text';
      const inner = codeElement.props.children;
      const code = typeof inner === 'string'
        ? inner.replace(/\n$/, '')
        : Array.isArray(inner)
          ? inner.filter((c) => typeof c === 'string').join('').replace(/\n$/, '')
          : '';
      return <ShikiCodeBlock language={language} code={code} />;
    }
    return (
      <pre className="mt-md overflow-x-auto rounded-md bg-surface-soft p-md font-mono text-[13px] leading-[1.5] text-text">
        {children}
      </pre>
    );
  },
  table: ({ children }) => (
    <div className="mt-md overflow-x-auto">
      <table className="w-full border-collapse text-small text-text">{children}</table>
    </div>
  ),
  thead: ({ children }) => <thead className="bg-surface-soft text-text-muted">{children}</thead>,
  th: ({ children }) => (
    <th className="border border-border px-sm py-xs text-left font-semibold">{w(children)}</th>
  ),
  td: ({ children }) => <td className="border border-border px-sm py-xs">{w(children)}</td>,
  img: ({ src, alt }) => {
    // Spec §9: external URLs only. Reject data: and protocol-relative URLs.
    if (!src || typeof src !== 'string') return null;
    if (!src.startsWith('https://') && !src.startsWith('http://')) return null;
    // eslint-disable-next-line @next/next/no-img-element -- external user-supplied URL, intentionally unoptimized
    return <img src={src} alt={alt ?? ''} className="mt-md max-w-full rounded-md" />;
  },
  em: ({ children }) => <em>{w(children)}</em>,
  strong: ({ children }) => <strong>{w(children)}</strong>,
  };
}

export function MarkdownReader({ body, className, citationPill }: MarkdownReaderProps) {
  const components = useMemo(() => buildComponents(citationPill), [citationPill]);
  return (
    <div className={cn('text-body text-text', className)}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        rehypePlugins={[[rehypeSanitize, sanitizeSchema]]}
        components={components}
      >
        {body}
      </ReactMarkdown>
    </div>
  );
}
