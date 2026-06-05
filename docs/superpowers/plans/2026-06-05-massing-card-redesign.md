# MassingResultCard Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Surface the brief document title in the `MassingResultCard` name slot ("매싱 모델 · cmux-drop"), replace the 📁 folder icon with a Lucide `Box` icon, and persist `brief_title` through the full data path so historical (post-reload) cards are consistent with streaming cards.

**Architecture:** `agent-tools` adds `briefTitle` to `MassingResult` (pulled from `state["detail"].title` which is already fetched). `rag-chat` propagates it: stores it in `chat.message_attachments.brief_title` and includes it in the `tool_result` SSE payload. The frontend reads it from both paths (streaming SSE and historical REST) and renders it in the name slot.

**Tech Stack:** Python/Pydantic (agent-tools), Java/Spring Boot/JDBC (rag-chat), PostgreSQL/Flyway (DB migration), TypeScript/React/Lucide (frontend)

---

## File Map

| File | Change |
|------|--------|
| `backend/fastapi/agent-tools/architecture/api/dtos.py` | Add `brief_title` to `MassingResult` |
| `backend/fastapi/agent-tools/architecture/app/nodes/respond.py` | Pass `state["detail"].title` to `MassingResult` |
| `backend/fastapi/agent-tools/tests/test_workflow.py` | Assert `result.brief_title` |
| `backend/springboot/rag-chat/rag-chat-infra/src/main/resources/db/migration/V202606050001__add_brief_title_to_attachments.sql` | New — ADD COLUMN |
| `backend/springboot/rag-chat/rag-chat-domain/src/main/java/com/playground/ragchat/domain/model/Attachment.java` | Add `briefTitle` field |
| `backend/springboot/rag-chat/rag-chat-infra/src/main/java/com/playground/ragchat/infrastructure/persistence/AttachmentRepositoryJdbcAdapter.java` | INSERT + SELECT `brief_title` |
| `backend/springboot/rag-chat/rag-chat-app/src/main/java/com/playground/ragchat/application/service/ChatTurnService.java` | Extract `briefTitle` from tool result; pass to `Attachment`; add to SSE |
| `backend/springboot/rag-chat/rag-chat-api/src/main/java/com/playground/ragchat/api/dto/SessionResponses.java` | Add `briefTitle` to `AttachmentWire` |
| `frontend/src/shared/api/chat.ts` | Add `briefTitle?` to `AttachmentWireDto` + `ToolResultEventPayload` |
| `frontend/src/shared/api/chat.sse.ts` | Extract `body.briefTitle` into `ToolResultEventPayload` |
| `frontend/src/views/chat/ChatPage.tsx` | Pass `a.briefTitle` in `attachmentToToolCard` |
| `frontend/src/features/chat-tool-card/MassingResultCard.tsx` | New icon + name slot |

---

## Task 1: Add `briefTitle` to agent-tools `MassingResult`

**Files:**
- Modify: `backend/fastapi/agent-tools/architecture/api/dtos.py`
- Modify: `backend/fastapi/agent-tools/architecture/app/nodes/respond.py`

- [ ] **Step 1: Add `brief_title` field to `MassingResult`**

In `dtos.py`, add `brief_title` right after `summary`:

```python
class MassingResult(BaseModel):
    """The LLM-visible tool result per ADR-20 §D2.
    ...
    """

    program_json: ProgramJsonWire = Field(alias="programJson")
    total_area_m2: float = Field(alias="totalAreaM2")
    floor_count: int = Field(alias="floorCount")  # above-grade
    basement_levels: int = Field(default=0, alias="basementLevels")
    summary: str  # Korean fixed format per ADR-18 §5 + §A18.5
    brief_title: str = Field(alias="briefTitle")  # document title from docs-api

    model_config = {"populate_by_name": True}
```

- [ ] **Step 2: Pass `state["detail"].title` in `respond.py`**

In `respond.py`, update the `MassingResult(...)` constructor call to include `briefTitle`:

```python
    return {
        "response": GenerateMassingResponse(
            result=MassingResult(
                programJson=program_json,
                totalAreaM2=total_area,
                floorCount=floors_above,
                basementLevels=basement_levels,
                summary=summary,
                briefTitle=state["detail"].title,
            ),
            artifact=MassingArtifact(
                filename=filename,
                contentType="application/octet-stream",
                sizeBytes=len(file_bytes),
                storageKey=storage_key,
            ),
        )
    }
```

- [ ] **Step 3: Update `test_workflow.py` to assert `brief_title`**

In `tests/test_workflow.py`, the `_FakeDocs.get_document` already returns `title="KFI 테스트 브리프"`. Add an assertion in `test_graph_runs_path_and_builds_envelope`:

```python
    assert result.brief_title == "KFI 테스트 브리프"
```

Add it right after `assert result.summary == "2실 · 지상 4층 + 지하 1층 · 총 31000 m²"`.

- [ ] **Step 4: Run the test**

```bash
cd backend/fastapi/agent-tools
.venv/bin/pytest tests/test_workflow.py::test_graph_runs_path_and_builds_envelope -v
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/fastapi/agent-tools/architecture/api/dtos.py \
        backend/fastapi/agent-tools/architecture/app/nodes/respond.py \
        backend/fastapi/agent-tools/tests/test_workflow.py
git commit -m "feat(agent-tools): add briefTitle to MassingResult response"
```

---

## Task 2: Flyway migration — add `brief_title` column

**Files:**
- Create: `backend/springboot/rag-chat/rag-chat-infra/src/main/resources/db/migration/V202606050001__add_brief_title_to_attachments.sql`

- [ ] **Step 1: Write the migration**

Create the file with this content:

```sql
-- ADR-20 §D1 amendment — add brief_title to chat.message_attachments so the
-- historical card can render "매싱 모델 · <briefTitle>" consistently with
-- the streaming card.
-- Nullable: legacy rows keep NULL and the frontend degrades gracefully.

ALTER TABLE chat.message_attachments
    ADD COLUMN brief_title TEXT NULL;
```

- [ ] **Step 2: Verify the migration filename is the next available version**

```bash
ls backend/springboot/rag-chat/rag-chat-infra/src/main/resources/db/migration/ | sort
```

Confirm `V202606050001` sorts after the current last migration (`V202606040001__create_message_attachments.sql`). If another migration was added in between, adjust the timestamp.

- [ ] **Step 3: Commit**

```bash
git add backend/springboot/rag-chat/rag-chat-infra/src/main/resources/db/migration/V202606050001__add_brief_title_to_attachments.sql
git commit -m "feat(db): add brief_title column to chat.message_attachments"
```

---

## Task 3: Update `Attachment` domain model

**Files:**
- Modify: `backend/springboot/rag-chat/rag-chat-domain/src/main/java/com/playground/ragchat/domain/model/Attachment.java`

- [ ] **Step 1: Add `briefTitle` to the record**

Replace the current record declaration:

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
        Instant createdAt) {
```

with:

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
        String briefTitle,
        Instant createdAt) {
```

- [ ] **Step 2: Update the compact constructor**

The compact constructor currently validates all fields. `briefTitle` is nullable so no null-check needed — just add it to the `toolArtifact` factory:

```java
    public static Attachment toolArtifact(
            AttachmentId id,
            MessageId messageId,
            String filename,
            String contentType,
            long sizeBytes,
            String storageKey,
            String toolName,
            String briefTitle,
            Instant createdAt) {
        return new Attachment(
                id, messageId, KIND_TOOL_ARTIFACT, filename, contentType,
                sizeBytes, storageKey, toolName, briefTitle, createdAt);
    }
```

- [ ] **Step 3: Compile**

```bash
cd backend/springboot/rag-chat
./gradlew :rag-chat-domain:compileJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add backend/springboot/rag-chat/rag-chat-domain/src/main/java/com/playground/ragchat/domain/model/Attachment.java
git commit -m "feat(rag-chat-domain): add briefTitle to Attachment record"
```

---

## Task 4: Update `AttachmentRepositoryJdbcAdapter`

**Files:**
- Modify: `backend/springboot/rag-chat/rag-chat-infra/src/main/java/com/playground/ragchat/infrastructure/persistence/AttachmentRepositoryJdbcAdapter.java`

- [ ] **Step 1: Update `save()` INSERT**

Replace:

```java
jdbc.update(
        "INSERT INTO chat.message_attachments "
                + "(id, message_id, kind, filename, content_type, size_bytes, storage_key, tool_name, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ps -> bindInsert(ps, attachment));
```

with:

```java
jdbc.update(
        "INSERT INTO chat.message_attachments "
                + "(id, message_id, kind, filename, content_type, size_bytes, storage_key, tool_name, brief_title, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ps -> bindInsert(ps, attachment));
```

- [ ] **Step 2: Update `saveAll()` batch INSERT**

Replace:

```java
jdbc.batchUpdate(
        "INSERT INTO chat.message_attachments "
                + "(id, message_id, kind, filename, content_type, size_bytes, storage_key, tool_name, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
```

with:

```java
jdbc.batchUpdate(
        "INSERT INTO chat.message_attachments "
                + "(id, message_id, kind, filename, content_type, size_bytes, storage_key, tool_name, brief_title, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
```

- [ ] **Step 3: Update `bindInsert()` to include `brief_title`**

Replace the current `bindInsert`:

```java
private static void bindInsert(PreparedStatement ps, Attachment a) throws SQLException {
    ps.setObject(1, a.id().value());
    ps.setObject(2, a.messageId().value());
    ps.setString(3, a.kind());
    ps.setString(4, a.filename());
    ps.setString(5, a.contentType());
    ps.setLong(6, a.sizeBytes());
    ps.setString(7, a.storageKey());
    ps.setString(8, a.toolName());
    ps.setObject(9, OffsetDateTime.ofInstant(a.createdAt(), ZoneOffset.UTC));
}
```

with:

```java
private static void bindInsert(PreparedStatement ps, Attachment a) throws SQLException {
    ps.setObject(1, a.id().value());
    ps.setObject(2, a.messageId().value());
    ps.setString(3, a.kind());
    ps.setString(4, a.filename());
    ps.setString(5, a.contentType());
    ps.setLong(6, a.sizeBytes());
    ps.setString(7, a.storageKey());
    ps.setString(8, a.toolName());
    ps.setString(9, a.briefTitle());
    ps.setObject(10, OffsetDateTime.ofInstant(a.createdAt(), ZoneOffset.UTC));
}
```

- [ ] **Step 4: Update `findOwned()` SELECT to include `brief_title`**

Replace:

```java
"SELECT a.id, a.message_id, a.kind, a.filename, a.content_type, "
        + "a.size_bytes, a.storage_key, a.tool_name, a.created_at "
        + "FROM chat.message_attachments a "
```

with:

```java
"SELECT a.id, a.message_id, a.kind, a.filename, a.content_type, "
        + "a.size_bytes, a.storage_key, a.tool_name, a.brief_title, a.created_at "
        + "FROM chat.message_attachments a "
```

- [ ] **Step 5: Update `findByMessages()` SELECT to include `brief_title`**

Replace:

```java
"SELECT id, message_id, kind, filename, content_type, "
        + "size_bytes, storage_key, tool_name, created_at "
        + "FROM chat.message_attachments "
```

with:

```java
"SELECT id, message_id, kind, filename, content_type, "
        + "size_bytes, storage_key, tool_name, brief_title, created_at "
        + "FROM chat.message_attachments "
```

- [ ] **Step 6: Update `attachmentRowMapper()` to read `brief_title`**

Replace:

```java
private RowMapper<Attachment> attachmentRowMapper() {
    return (rs, n) -> new Attachment(
            AttachmentId.of((UUID) rs.getObject("id")),
            MessageId.of((UUID) rs.getObject("message_id")),
            rs.getString("kind"),
            rs.getString("filename"),
            rs.getString("content_type"),
            rs.getLong("size_bytes"),
            rs.getString("storage_key"),
            rs.getString("tool_name"),
            rs.getTimestamp("created_at").toInstant());
}
```

with:

```java
private RowMapper<Attachment> attachmentRowMapper() {
    return (rs, n) -> new Attachment(
            AttachmentId.of((UUID) rs.getObject("id")),
            MessageId.of((UUID) rs.getObject("message_id")),
            rs.getString("kind"),
            rs.getString("filename"),
            rs.getString("content_type"),
            rs.getLong("size_bytes"),
            rs.getString("storage_key"),
            rs.getString("tool_name"),
            rs.getString("brief_title"),
            rs.getTimestamp("created_at").toInstant());
}
```

- [ ] **Step 7: Compile**

```bash
cd backend/springboot/rag-chat
./gradlew :rag-chat-infra:compileJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add backend/springboot/rag-chat/rag-chat-infra/src/main/java/com/playground/ragchat/infrastructure/persistence/AttachmentRepositoryJdbcAdapter.java
git commit -m "feat(rag-chat-infra): persist and read brief_title in attachment repository"
```

---

## Task 5: Update `ChatTurnService` to propagate `briefTitle`

**Files:**
- Modify: `backend/springboot/rag-chat/rag-chat-app/src/main/java/com/playground/ragchat/application/service/ChatTurnService.java`

- [ ] **Step 1: Extract `briefTitle` from tool result JSON in `storeArtifact`**

The `storeArtifact` method currently receives `ToolArtifact artifact` (which has no `briefTitle`) and the `JsonNode body` is in scope at the call site. We need to pass `briefTitle` into `storeArtifact`.

First, update the `storeArtifact` signature to accept `briefTitle`:

```java
private Attachment storeArtifact(
        ToolArtifact artifact,
        String toolName,
        String briefTitle,
        SessionId sessionId,
        MessageId assistantMessageId,
        List<Attachment> stagedAttachments) {
```

Inside `storeArtifact`, update the `Attachment.toolArtifact(...)` call to pass `briefTitle`:

```java
            Attachment attachment = Attachment.toolArtifact(
                    attachmentId,
                    assistantMessageId,
                    artifact.filename(),
                    artifact.contentTypeOrDefault(),
                    artifact.sizeBytes(),
                    artifact.storageKey(),
                    toolName,
                    briefTitle,
                    clock.instant());
```

- [ ] **Step 2: Extract `briefTitle` at the call site**

Find the block that calls `storeArtifact` (currently inside `dispatchTool`):

```java
            if (s.artifact() != null) {
                attachment = storeArtifact(
                        s.artifact(), desc.name(), sessionId, assistantMessageId, stagedAttachments);
            }
```

Replace with:

```java
            if (s.artifact() != null) {
                String briefTitle = s.body() != null && s.body().has("briefTitle")
                        ? s.body().get("briefTitle").asText(null)
                        : null;
                attachment = storeArtifact(
                        s.artifact(), desc.name(), briefTitle,
                        sessionId, assistantMessageId, stagedAttachments);
            }
```

- [ ] **Step 3: Add `briefTitle` to the SSE `tool_result` enrichment**

In `enrichResultForSse`, add `briefTitle` to the enriched node so the frontend SSE parser can read it.

Inside the `if (attachment == null)` check, find the block after `enriched.put("fileUrl", downloadUrl)`:

```java
        enriched.put("fileUrl", downloadUrl);
        return enriched;
```

Add the `briefTitle` field from the attachment:

```java
        enriched.put("fileUrl", downloadUrl);
        if (attachment.briefTitle() != null) {
            enriched.put("briefTitle", attachment.briefTitle());
        }
        return enriched;
```

- [ ] **Step 4: Compile the app module**

```bash
cd backend/springboot/rag-chat
./gradlew :rag-chat-app:compileJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Compile the whole rag-chat project**

```bash
./gradlew :rag-chat-api:compileJava :rag-chat-infra:compileJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add backend/springboot/rag-chat/rag-chat-app/src/main/java/com/playground/ragchat/application/service/ChatTurnService.java
git commit -m "feat(rag-chat-app): propagate briefTitle through tool result to SSE + attachment"
```

---

## Task 6: Update `AttachmentWire` in the API DTO + history response

**Files:**
- Modify: `backend/springboot/rag-chat/rag-chat-api/src/main/java/com/playground/ragchat/api/dto/SessionResponses.java`

- [ ] **Step 1: Add `briefTitle` to `AttachmentWire`**

Find the `AttachmentWire` record:

```java
    public record AttachmentWire(
            UUID id,
            String filename,
            String contentType,
            long sizeBytes,
            String downloadUrl,
            String toolName) {}
```

Replace with:

```java
    public record AttachmentWire(
            UUID id,
            String filename,
            String contentType,
            long sizeBytes,
            String downloadUrl,
            String toolName,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            String briefTitle) {}
```

(`@JsonInclude(NON_NULL)` is already imported at the top of `SessionResponses.java` — it suppresses the `briefTitle` field entirely from old/legacy rows where it's null.)

- [ ] **Step 2: Update the mapper to pass `briefTitle`**

Find the `attachmentWire` construction in `MessageHistoryResponse.from`:

```java
                AttachmentWire attachmentWire = m.attachment()
                        .map(a -> new AttachmentWire(
                                a.id().value(),
                                a.filename(),
                                a.contentType(),
                                a.sizeBytes(),
                                ATTACHMENT_DOWNLOAD_PREFIX + a.id().value(),
                                a.toolName()))
                        .orElse(null);
```

Replace with:

```java
                AttachmentWire attachmentWire = m.attachment()
                        .map(a -> new AttachmentWire(
                                a.id().value(),
                                a.filename(),
                                a.contentType(),
                                a.sizeBytes(),
                                ATTACHMENT_DOWNLOAD_PREFIX + a.id().value(),
                                a.toolName(),
                                a.briefTitle()))
                        .orElse(null);
```

- [ ] **Step 3: Compile**

```bash
cd backend/springboot/rag-chat
./gradlew :rag-chat-api:compileJava
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Run rag-chat tests**

```bash
./gradlew :rag-chat-infra:test :rag-chat-app:test
```

Expected: all tests pass. If `ToolCallingE2ETest` references `Attachment.toolArtifact(...)` with the old signature, update the test call site to include a `null` `briefTitle` argument.

- [ ] **Step 5: Commit**

```bash
git add backend/springboot/rag-chat/rag-chat-api/src/main/java/com/playground/ragchat/api/dto/SessionResponses.java
git commit -m "feat(rag-chat-api): expose briefTitle on AttachmentWire history DTO"
```

---

## Task 7: Frontend — extend shared type declarations

**Files:**
- Modify: `frontend/src/shared/api/chat.ts`

- [ ] **Step 1: Add `briefTitle` to `AttachmentWireDto`**

Find:

```typescript
export interface AttachmentWireDto {
  id: string;
  filename: string;
  contentType: string;
  sizeBytes: number;
  /** Gateway-relative download URL: `/api/rag/chat/attachments/{id}`. */
  downloadUrl: string;
  toolName: string;
}
```

Replace with:

```typescript
export interface AttachmentWireDto {
  id: string;
  filename: string;
  contentType: string;
  sizeBytes: number;
  /** Gateway-relative download URL: `/api/rag/chat/attachments/{id}`. */
  downloadUrl: string;
  toolName: string;
  /** Document title of the brief that produced this artifact. Absent on legacy rows. */
  briefTitle?: string;
}
```

- [ ] **Step 2: Add `briefTitle` to `ToolResultEventPayload`**

Find:

```typescript
export interface ToolResultEventPayload {
  id: string;
  name: string;
  /** One-line user-facing summary. M8 emits Korean per ADR-18 §5. Optional for plain M7 tools. */
  summary?: string;
  /** Relative download URL for file-producing tools. */
  outputUrl?: string;
  /** Tool-specific structured payload. M8: see {@link MassingProgramJson}. */
  programJson?: Record<string, unknown>;
  /** Reserved for non-file tools (e.g. image-gen returning an inline preview). */
  metadata?: Record<string, unknown>;
}
```

Replace with:

```typescript
export interface ToolResultEventPayload {
  id: string;
  name: string;
  /** One-line user-facing summary. M8 emits Korean per ADR-18 §5. Optional for plain M7 tools. */
  summary?: string;
  /** Relative download URL for file-producing tools. */
  outputUrl?: string;
  /** Document title of the brief that produced this artifact (M8). */
  briefTitle?: string;
  /** Tool-specific structured payload. M8: see {@link MassingProgramJson}. */
  programJson?: Record<string, unknown>;
  /** Reserved for non-file tools (e.g. image-gen returning an inline preview). */
  metadata?: Record<string, unknown>;
}
```

- [ ] **Step 3: Type-check**

```bash
cd frontend
npx tsc --noEmit
```

Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/shared/api/chat.ts
git commit -m "feat(frontend/types): add briefTitle to AttachmentWireDto and ToolResultEventPayload"
```

---

## Task 8: Frontend — SSE parser and history adapter

**Files:**
- Modify: `frontend/src/shared/api/chat.sse.ts`
- Modify: `frontend/src/views/chat/ChatPage.tsx`

- [ ] **Step 1: Extract `briefTitle` in the SSE `tool_result` parser**

In `chat.sse.ts`, find the `tool_result` case:

```typescript
      const payload: ToolResultEventPayload = {
        id: raw.id,
        name: raw.name,
        summary: typeof body.summary === 'string' ? body.summary : undefined,
        outputUrl: typeof body.fileUrl === 'string' ? body.fileUrl : undefined,
        programJson:
          typeof body.programJson === 'object' && body.programJson !== null
            ? (body.programJson as Record<string, unknown>)
            : undefined,
        metadata:
          typeof body.metadata === 'object' && body.metadata !== null
            ? (body.metadata as Record<string, unknown>)
            : undefined,
      };
```

Replace with:

```typescript
      const payload: ToolResultEventPayload = {
        id: raw.id,
        name: raw.name,
        summary: typeof body.summary === 'string' ? body.summary : undefined,
        outputUrl: typeof body.fileUrl === 'string' ? body.fileUrl : undefined,
        briefTitle: typeof body.briefTitle === 'string' ? body.briefTitle : undefined,
        programJson:
          typeof body.programJson === 'object' && body.programJson !== null
            ? (body.programJson as Record<string, unknown>)
            : undefined,
        metadata:
          typeof body.metadata === 'object' && body.metadata !== null
            ? (body.metadata as Record<string, unknown>)
            : undefined,
      };
```

- [ ] **Step 2: Pass `briefTitle` in `attachmentToToolCard` in `ChatPage.tsx`**

Find:

```typescript
function attachmentToToolCard(a: AttachmentWireDto, messageContent?: string): ToolCardState {
  const toolCall: ToolCallPayload = { id: a.id, name: a.toolName, args: {} };
  const summary = messageContent ? messageContent.split('\n')[0] : undefined;
  const toolResult: ToolResultPayload = { id: a.id, name: a.toolName, outputUrl: a.downloadUrl, summary };
  return { kind: 'result', toolCall, toolResult, calledAt: 0, resolvedAt: 0 };
}
```

Replace with:

```typescript
function attachmentToToolCard(a: AttachmentWireDto, messageContent?: string): ToolCardState {
  const toolCall: ToolCallPayload = { id: a.id, name: a.toolName, args: {} };
  const summary = messageContent ? messageContent.split('\n')[0] : undefined;
  const toolResult: ToolResultPayload = {
    id: a.id,
    name: a.toolName,
    outputUrl: a.downloadUrl,
    summary,
    briefTitle: a.briefTitle,
  };
  return { kind: 'result', toolCall, toolResult, calledAt: 0, resolvedAt: 0 };
}
```

- [ ] **Step 3: Type-check**

```bash
cd frontend
npx tsc --noEmit
```

Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/shared/api/chat.sse.ts frontend/src/views/chat/ChatPage.tsx
git commit -m "feat(frontend): wire briefTitle from SSE and history into ToolResultPayload"
```

---

## Task 9: Frontend — redesign `MassingResultCard`

**Files:**
- Modify: `frontend/src/features/chat-tool-card/MassingResultCard.tsx`

- [ ] **Step 1: Replace `MassingIcon` and update the in-flight card**

At the top of `MassingResultCard.tsx`, add the `Box` import from Lucide (it's already using `ChevronDown`, `ChevronRight`, `Download` from lucide-react):

```typescript
import { Box, ChevronDown, ChevronRight, Download } from 'lucide-react';
```

Replace the `MassingIcon` function:

```typescript
// BEFORE
function MassingIcon() {
  return <span aria-hidden="true">📁</span>;
}

// AFTER — remove MassingIcon entirely; use Box inline
```

Update the in-flight card render:

```tsx
  if (state.kind === 'in_flight') {
    return (
      <ToolResultCard
        ariaLabel="Tool call in flight: 매싱 모델"
        icon={<Box size={18} aria-hidden="true" strokeWidth={1.75} />}
        name={<span className="text-[14px] font-semibold text-text">매싱 모델</span>}
        summary={
          <span className="inline-flex items-center gap-sm text-text-muted">
            <span>Running…</span>
            <Spinner />
          </span>
        }
        primaryAction={null}
        footer={null}
      />
    );
  }
```

- [ ] **Step 2: Update the result card with the new name slot**

Replace the result card render's `icon` and `name` props:

```tsx
  return (
    <ToolResultCard
      ariaLabel="Tool result: 매싱 모델"
      icon={<Box size={18} aria-hidden="true" strokeWidth={1.75} />}
      name={
        <span className="text-[14px] font-semibold text-text">
          매싱 모델
          {state.toolResult.briefTitle && (
            <span className="font-normal text-text-muted"> · {state.toolResult.briefTitle}</span>
          )}
        </span>
      }
      summary={
        <span className="font-medium text-text">{state.toolResult.summary}</span>
      }
      primaryAction={hasDownloadUrl ? <DownloadDotThreeDmButton href={state.toolResult.outputUrl!} /> : null}
      footer={
        hasProgram ? (
          // ... accordion button — unchanged
```

Keep everything from `footer={` onward unchanged.

- [ ] **Step 3: Type-check**

```bash
cd frontend
npx tsc --noEmit
```

Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/features/chat-tool-card/MassingResultCard.tsx
git commit -m "feat(frontend): redesign MassingResultCard — Box icon + brief title name slot"
```

---

## Task 10: Build and smoke-test

- [ ] **Step 1: Rebuild affected containers**

```bash
cd infra
docker compose build rag-chat agent-tools frontend
docker compose up -d rag-chat agent-tools frontend
```

- [ ] **Step 2: Run Flyway migration**

Flyway runs on startup. Confirm:

```bash
docker compose logs rag-chat 2>&1 | grep -i "flyway\|migration\|brief_title" | tail -10
```

Expected: `Successfully applied 1 migration to schema "chat"` (or similar Flyway success line).

- [ ] **Step 3: Smoke test — new massing generation**

In the chat UI, send a message requesting a massing for a brief. After the tool completes:

- The card should show `매싱 모델 · <document title>` (not `generate_massing`)
- The icon should be a cube outline (not 📁)
- The Korean one-liner summary should still appear below the name
- The "Download .3dm" button should still work

- [ ] **Step 4: Smoke test — historical card (post-reload)**

After the new card appears, reload the page. The historical card should:

- Show the same `매싱 모델 · <document title>` name
- Show the same summary
- Show the Download button

- [ ] **Step 5: Smoke test — legacy card (pre-migration row)**

Any attachment rows that existed before this migration have `brief_title = NULL`. Their cards should show just `매싱 모델` (no suffix) — graceful degradation.
