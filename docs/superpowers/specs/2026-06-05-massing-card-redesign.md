# MassingResultCard Redesign — Design Spec

**Date**: 2026-06-05  
**Milestone**: M8 (post-fix iteration)

---

## Problem

`MassingResultCard` shows `generate_massing` (internal tool name) in its name slot and `📁` (folder icon) — both are meaningless to the user. The card also lacks the brief title on historical (post-reload) views because `brief_title` is not stored with the attachment record.

---

## Goal

1. Name slot: `매싱 모델 · <briefTitle>` (human-readable, identifies which brief produced the model)
2. Icon: replace `📁` emoji with Lucide `Box` (3D cube — matches "geometric artifact" semantics)
3. Consistent between streaming cards and historical cards (post-reload)

---

## Design Decision

**Option C** was chosen: `매싱 모델 · <briefTitle>` where briefTitle is the document title from docs-api (e.g., "cmux-drop"). The muted suffix distinguishes type (`매싱 모델`) from provenance (`· cmux-drop`).

**In-flight state** (spinner, briefTitle not yet known): show `매싱 모델` only (no suffix).  
**Historical cards** with missing briefTitle (legacy rows): graceful degradation to `매싱 모델` only.

---

## Data Flow

```
docs-api  ──→  agent-tools/fetch_brief  →  state["detail"].title
                                                ↓
                    respond.py adds briefTitle to MassingResult JSON
                                                ↓
              rag-chat ChatTurnService parses briefTitle from tool result
              ├── SSE tool_result event  →  ToolResultEventPayload.briefTitle
              └── Attachment.briefTitle  →  chat.message_attachments.brief_title
                                                ↓
                         Frontend MassingResultCard renders
                         "매싱 모델 · <briefTitle>"
```

---

## Touch Points

### 1. agent-tools (Python) — `dtos.py`

Add `brief_title` to `MassingResult`:

```python
class MassingResult(BaseModel):
    # ... existing fields ...
    brief_title: str = Field(alias="briefTitle")
```

### 2. agent-tools (Python) — `respond.py`

Pull `state["detail"].title` into the result:

```python
return {
    "response": GenerateMassingResponse(
        result=MassingResult(
            # ... existing fields ...
            briefTitle=state["detail"].title,
        ),
        artifact=MassingArtifact(...),
    )
}
```

### 3. Flyway migration — new file `V202606050001__add_brief_title_to_attachments.sql`

```sql
ALTER TABLE chat.message_attachments
    ADD COLUMN brief_title TEXT NULL;
```

File path: `backend/springboot/rag-chat/rag-chat-infra/src/main/resources/db/migration/V202606050001__add_brief_title_to_attachments.sql`

### 4. rag-chat domain — `Attachment.java`

Add `briefTitle` field (nullable `String`):

```java
public record Attachment(
        AttachmentId id,
        MessageId messageId,
        String kind,
        String filename,
        String contentType,
        long sizeBytes,
        String storageKey,
        String toolName,
        @Nullable String briefTitle,   // ← new
        Instant createdAt) { ... }
```

Update `toolArtifact()` factory to accept `briefTitle`.

### 5. rag-chat infra — `AttachmentRepositoryJdbcAdapter.java`

- `save()` / `saveAll()`: include `brief_title` in INSERT
- `findOwned()` / `findByMessages()`: SELECT `brief_title`, add to row mapper
- `bindInsert()`: add `ps.setString(9, a.briefTitle())` (shift `created_at` to 10)

### 6. rag-chat app — `ToolArtifact.java` + `ChatTurnService.java`

`ToolArtifact` (the intermediate value object between tool result and persistence):
- Add `briefTitle` field

`ChatTurnService`:
- When processing tool result JSON, extract `result.briefTitle` (nullable, `String`)
- Pass to `ToolArtifact.briefTitle`
- Pass to `Attachment.toolArtifact()` factory
- Include `briefTitle` in the `tool_result` SSE event payload

### 7. rag-chat api — `AttachmentWireDto.java`

Add `briefTitle` (nullable):

```java
public record AttachmentWireDto(
        String id,
        String toolName,
        String downloadUrl,
        @Nullable String briefTitle) {}
```

Update the mapper from `Attachment` → `AttachmentWireDto`.

### 8. frontend — `chat.ts` (`ToolResultEventPayload`)

```typescript
export interface ToolResultEventPayload {
  id: string;
  name: string;
  summary?: string;
  outputUrl?: string;
  briefTitle?: string;       // ← new
  programJson?: Record<string, unknown>;
  metadata?: Record<string, unknown>;
}
```

### 9. frontend — `chat.sse.ts`

In the `tool_result` SSE event parser, extract `result.briefTitle`:

```typescript
briefTitle: typeof result.briefTitle === 'string' ? result.briefTitle : undefined,
```

### 10. frontend — `AttachmentWireDto` TypeScript type (in `shared/api/chat.ts` or wherever defined)

Add `briefTitle?: string`.

### 11. frontend — `ChatPage.tsx` (`attachmentToToolCard`)

```typescript
function attachmentToToolCard(a: AttachmentWireDto, messageContent?: string): ToolCardState {
  const summary = messageContent ? messageContent.split('\n')[0] : undefined;
  const toolResult: ToolResultPayload = {
    id: a.id,
    name: a.toolName,
    outputUrl: a.downloadUrl,
    summary,
    briefTitle: a.briefTitle,   // ← new
  };
  return { kind: 'result', toolCall: { id: a.id, name: a.toolName, args: {} }, toolResult, calledAt: 0, resolvedAt: 0 };
}
```

### 12. frontend — `MassingResultCard.tsx`

**Icon**: replace `MassingIcon` (📁 emoji) with Lucide `Box` component (22px, `text-text` color).

**Name slot** (both in-flight and result states):

```tsx
// in-flight
name={<span className="text-[14px] font-semibold text-text">매싱 모델</span>}

// result — with optional brief title suffix
name={
  <span className="text-[14px] font-semibold text-text">
    매싱 모델
    {state.toolResult.briefTitle && (
      <span className="font-normal text-text-muted"> · {state.toolResult.briefTitle}</span>
    )}
  </span>
}
```

Update `ariaLabel` strings: `"Tool call in flight: 매싱 모델"` / `"Tool result: 매싱 모델"`.

---

## Migration Safety

`brief_title TEXT NULL` — nullable, no default required. Existing rows keep `NULL` and the frontend degrades gracefully to "매싱 모델" (no suffix). Zero downtime; no backfill needed.

---

## Out of Scope

- Backfilling `brief_title` on existing attachment rows (not worth the complexity for legacy data)
- i18n of "매싱 모델" label (Korean-primary product, no locale toggle planned in M8)
- Changing the `summary` slot format (Korean one-liner stays as-is)
