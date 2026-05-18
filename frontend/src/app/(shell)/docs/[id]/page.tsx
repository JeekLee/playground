import type { Metadata } from 'next';
import { fetchDocByIdServerSide } from '@/shared/api/docs.server';
import { loadMe } from '@/features/me';
import { DocEditor, DocNotFound, DocReader } from '@/views/doc-detail';

/**
 * `/docs/{id}` — single-document route.
 *
 * Per M2 spec v5 §6.1 authorization rule and §7.2:
 *  - The route is auth-optional: anonymous callers can read public docs.
 *  - The docs API returns 404 for `(visibility='private') AND (caller !=
 *    author)`. We propagate that 404 to the user as the `DocNotFound`
 *    card.
 *  - When the caller IS the author, we render the in-place editor
 *    (`DocEditor`). Otherwise we render the reader pipeline (`DocReader`).
 *
 * OpenGraph + Twitter Card meta per spec §7.4 are emitted via
 * `generateMetadata`. For private docs (404 to the caller) we return a
 * `Not found` shape so unfurlers don't index the existence of a
 * private title.
 */

export const dynamic = 'force-dynamic';

type PageProps = {
  params: { id: string };
  searchParams: { published?: string };
};

export async function generateMetadata({ params }: PageProps): Promise<Metadata> {
  const result = await fetchDocByIdServerSide(params.id);
  if (result.kind !== 'ok') {
    return { title: "Not found · JeekLee's playground" };
  }
  const doc = result.value;
  // Private docs viewed by the owner still SSR; we don't emit OG tags for
  // them (only the owner sees them, no unfurl surface needed).
  if (doc.visibility !== 'public') {
    return { title: `${doc.title} · JeekLee's playground` };
  }
  return {
    title: `${doc.title} · JeekLee's playground`,
    description: doc.excerpt,
    openGraph: {
      title: doc.title,
      description: doc.excerpt,
      type: 'article',
      url: `/docs/${doc.id}`,
      publishedTime: doc.publishedAt,
      authors: [doc.author.displayName],
    },
    twitter: {
      card: 'summary_large_image',
      title: doc.title,
      description: doc.excerpt,
    },
  };
}

export default async function DocByIdRoute({ params, searchParams }: PageProps) {
  const [me, docResult] = await Promise.all([
    loadMe(),
    fetchDocByIdServerSide(params.id),
  ]);

  if (docResult.kind === 'not-found') {
    return <DocNotFound />;
  }
  if (docResult.kind !== 'ok') {
    return <DocNotFound />;
  }

  const doc = docResult.value;
  const isOwner =
    me.kind === 'authenticated' && me.user.id === doc.author.id;

  if (isOwner) {
    return <DocEditor doc={doc} publishedFlash={searchParams.published === '1'} />;
  }
  return <DocReader doc={doc} />;
}
