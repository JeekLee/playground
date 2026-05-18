'use client';

import type { Highlighter } from 'shiki';

/**
 * Shared Shiki highlighter singleton — one instance covers both the
 * BlockNote editor's CodeBlock (via `useCreateBlockNote({ codeBlock:
 * { createHighlighter, supportedLanguages } })`) and the public
 * Markdown reader's fenced-code blocks. Lazily loaded on first use so
 * the cost only lands when the user actually visits a doc surface.
 *
 * Theme: `github-light` matches our cream playground palette well
 * enough — neutral whites + muted token colors that don't fight the
 * design system's olive/khaki accents. The token colors are inlined
 * into spans by shiki at highlight time so no extra CSS variable
 * plumbing is needed.
 */

export const SUPPORTED_LANGS = [
    'bash',
    'shellscript',
    'css',
    'diff',
    'dockerfile',
    'go',
    'gradle',
    'graphql',
    'groovy',
    'html',
    'java',
    'javascript',
    'json',
    'jsx',
    'kotlin',
    'markdown',
    'nginx',
    'php',
    'python',
    'rust',
    'scala',
    'shell',
    'sql',
    'svelte',
    'swift',
    'toml',
    'tsx',
    'typescript',
    'vue',
    'xml',
    'yaml',
] as const;

export const SHIKI_THEME = 'github-light';

let highlighterPromise: Promise<Highlighter> | null = null;

export function getSharedHighlighter(): Promise<Highlighter> {
    if (!highlighterPromise) {
        // Dynamic import so the shiki bundle (~150 KB) lands only on first
        // surface that actually needs syntax highlighting.
        highlighterPromise = import('shiki').then(({ createHighlighter }) =>
            createHighlighter({
                langs: [...SUPPORTED_LANGS],
                themes: [SHIKI_THEME],
            }),
        );
    }
    return highlighterPromise;
}

/**
 * BlockNote `supportedLanguages` shape — mirrors {@link SUPPORTED_LANGS}
 * but in the `{ key: { name, aliases } }` map BlockNote expects. The
 * `name` is what appears in the language picker dropdown on the
 * editor's code block.
 */
export const BLOCKNOTE_SUPPORTED_LANGUAGES: Record<
    string,
    { name: string; aliases?: string[] }
> = {
    bash: { name: 'Bash', aliases: ['shell', 'sh', 'shellscript', 'zsh'] },
    css: { name: 'CSS' },
    diff: { name: 'Diff' },
    dockerfile: { name: 'Dockerfile' },
    go: { name: 'Go', aliases: ['golang'] },
    gradle: { name: 'Gradle' },
    graphql: { name: 'GraphQL', aliases: ['gql'] },
    groovy: { name: 'Groovy' },
    html: { name: 'HTML' },
    java: { name: 'Java' },
    javascript: { name: 'JavaScript', aliases: ['js'] },
    json: { name: 'JSON' },
    jsx: { name: 'JSX' },
    kotlin: { name: 'Kotlin', aliases: ['kt'] },
    markdown: { name: 'Markdown', aliases: ['md'] },
    nginx: { name: 'Nginx' },
    php: { name: 'PHP' },
    python: { name: 'Python', aliases: ['py'] },
    rust: { name: 'Rust', aliases: ['rs'] },
    scala: { name: 'Scala' },
    sql: { name: 'SQL' },
    svelte: { name: 'Svelte' },
    swift: { name: 'Swift' },
    toml: { name: 'TOML' },
    tsx: { name: 'TSX' },
    typescript: { name: 'TypeScript', aliases: ['ts'] },
    vue: { name: 'Vue' },
    xml: { name: 'XML' },
    yaml: { name: 'YAML', aliases: ['yml'] },
};
