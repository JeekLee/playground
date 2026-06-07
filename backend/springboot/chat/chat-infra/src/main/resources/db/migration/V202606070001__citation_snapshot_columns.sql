-- agentic-search spec D2: 인용 스냅샷 영속 — 히스토리 리로드가 docs 스키마를
-- 읽지 않도록 영속 시점 값을 동결한다. 데이터 리셋(2026-06-07) 직후라 레거시
-- 행 없음.
ALTER TABLE chat.message_citations
    ADD COLUMN title      TEXT NULL,
    ADD COLUMN excerpt    TEXT NULL,
    ADD COLUMN visibility TEXT NULL;

COMMENT ON COLUMN chat.message_citations.title IS
  'Snapshot of docs.documents.title at persist time (agentic-search spec D2) — history reload no longer joins docs.';
COMMENT ON COLUMN chat.message_citations.excerpt IS
  'Snapshot of the cited chunk excerpt at persist time (agentic-search spec D2).';
COMMENT ON COLUMN chat.message_citations.visibility IS
  'Snapshot of the cited chunk visibility (public|private) at persist time (agentic-search spec D2).';
