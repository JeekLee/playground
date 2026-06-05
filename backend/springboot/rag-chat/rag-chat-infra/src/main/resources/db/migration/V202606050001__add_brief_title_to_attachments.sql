-- ADR-20 §D1 amendment — add brief_title to chat.message_attachments so the
-- historical card can render "매싱 모델 · <briefTitle>" consistently with
-- the streaming card.
-- Nullable: legacy rows keep NULL and the frontend degrades gracefully.

ALTER TABLE chat.message_attachments
    ADD COLUMN brief_title TEXT NULL;
