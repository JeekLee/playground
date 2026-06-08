-- SP3b: chat.message_citations corpus-무관 전환. 데이터 리셋(2026-06-08) 직후 — 빈 테이블.
-- forward-only (undo 없음). DEFAULT 'document' + DROP DEFAULT: NOT NULL 추가를 빈/비빈
-- 어느 상태에서도 안전하게, 이후 insert는 source_type 명시 강제.
ALTER TABLE chat.message_citations
    DROP COLUMN document_id,
    DROP COLUMN chunk_index,
    DROP COLUMN visibility,
    DROP COLUMN excerpt,
    ADD COLUMN source_type TEXT NOT NULL DEFAULT 'document',
    ADD COLUMN content     TEXT NULL,
    ADD COLUMN uri         TEXT NULL;
ALTER TABLE chat.message_citations ALTER COLUMN source_type DROP DEFAULT;
DROP INDEX IF EXISTS chat.chat_message_citations_by_document;

COMMENT ON COLUMN chat.message_citations.source_type IS
  'Corpus discriminator ("document" today) frozen at persist time (SP3b spec D6).';
COMMENT ON COLUMN chat.message_citations.content IS
  'Snapshot of the cited text (≤600 chars) at persist time (SP3b spec D6).';
COMMENT ON COLUMN chat.message_citations.uri IS
  'Snapshot of the absolute source access URL at persist time (SP3b spec D6).';
