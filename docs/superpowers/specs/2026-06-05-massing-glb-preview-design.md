# Massing .glb Preview — Design Spec

**Date**: 2026-06-05
**Milestone**: M8 (post-fix iteration)

---

## Problem

`MassingResultCard`는 `.3dm` 다운로드 버튼만 제공하고 있어서, 파일을 열지 않으면 어떤 모델인지 확인할 방법이 없다. Rhino가 없는 사용자는 더욱 그렇다.

---

## Goal

1. agent-tools가 `.3dm` 생성 시점에 `.glb`도 함께 MinIO에 저장
2. rag-chat에 `GET /attachments/{id}/preview` 엔드포인트 추가 — `.glb` blob 스트림
3. `MassingResultCard`에 "3D 미리보기" accordion 추가 — `<model-viewer>` 웹 컴포넌트로 인라인 렌더링

---

## Design Decisions

### .glb 생성 위치
agent-tools `store` 노드를 `store_3dm` / `store_glb`로 분리. 각 노드가 단일 책임을 가짐.

`.glb`는 `state["boxes"]` (`list[RoomBox]`)에서 `trimesh`로 직접 생성 — `.3dm` round-trip 불필요 (geometry가 이미 순수 mesh).

### 키 네이밍 컨벤션
```
architecture/massing/{date}/{uuid}/massing-{slug}-{ts}.3dm   ← store_3dm
architecture/massing/{date}/{uuid}/massing-{slug}-{ts}.glb   ← store_glb
```
같은 prefix, 확장자만 다름. DB/도메인 모델 변경 없음.

### preview 엔드포인트 범용성
`/attachments/{id}/preview`는 contentType-aware 범용 엔드포인트로 설계. 현재는 `.3dm` → `.glb`만 구현하고, 향후 `image/*` (원본/썸네일), `video/*` (키프레임) 등으로 확장 가능. 지원하지 않는 타입은 `415 Unsupported Media Type` 반환.

### .glb 다운로드 미노출
`.glb`는 preview 전용 내부 artifact. 다운로드 버튼은 `.3dm` 단일 유지. `.glb`는 NURBS 원본이 아닌 mesh 변환본이므로 deliverable로 적합하지 않음.

### 뷰어 배치
"3D 미리보기" accordion — 카드 내 인라인 확장 (채팅 흐름 유지). "Program details" accordion 위에 위치.

---

## Data Flow

```
agent-tools serialize node
    ↓ state["file_bytes"] + state["boxes"]
store_3dm → MinIO: massing-{slug}-{ts}.3dm → state["storage_key"]
store_glb → MinIO: massing-{slug}-{ts}.glb  (key derived from storage_key)
    ↓
rag-chat ChatTurnService (unchanged — storage_key만 사용)
    ↓
GET /attachments/{id}/preview
    → attachment.storageKey().replace(".3dm", ".glb")
    → MinIO stream
    ↓
<model-viewer src="/api/rag/chat/attachments/{id}/preview" />
```

---

## Touch Points

### 1. agent-tools — `architecture/app/nodes/store_3dm.py`

기존 `store.py` 이름 변경. 내용 동일 — `.3dm` 업로드 후 `state["storage_key"]` 세팅.

```python
def store_3dm(state: MassingState) -> dict:
    # ... (기존 store 로직 그대로)
    return {"storage_key": storage_key}
```

### 2. agent-tools — `architecture/app/nodes/store_glb.py` (신규)

```python
def store_glb(state: MassingState) -> dict:
    try:
        glb_key = state["storage_key"].replace(".3dm", ".glb")
        glb_bytes = _build_glb(state["boxes"])
        upload_to_key(
            file_bytes=glb_bytes,
            key=glb_key,
            content_type="model/gltf-binary",
            settings=get_settings(),
        )
    except Exception:
        logger.warning("store_glb failed — preview unavailable, continuing", exc_info=True)
    return {}
```

`_build_glb(boxes)`: `trimesh`으로 각 `RoomBox` → `trimesh.creation.box(extents, transform)` → scene → `.glb` export. 반환값 없음 — respond 노드는 `.glb` key 불필요. 실패 시 예외를 catch하고 `{}` 반환 — `.3dm` 저장은 이미 완료됐으므로 워크플로우 중단 없음.

### 3. agent-tools — `architecture/infra/blob_storage.py`

`upload_to_key(file_bytes, key, content_type, settings)` 추가. 기존 `upload_artifact`는 내부에서 UUID를 생성하지만, 이 함수는 key를 직접 받아 업로드.

```python
def upload_to_key(
    file_bytes: bytes,
    key: str,
    content_type: str,
    settings: Settings,
) -> None:
    client, bucket = _get_client(settings)
    client.put_object(
        bucket_name=bucket,
        object_name=key,
        data=io.BytesIO(file_bytes),
        length=len(file_bytes),
        content_type=content_type,
    )
```

### 4. agent-tools — `architecture/app/workflow.py`

노드 순서 변경:
```
... → serialize → store_3dm → store_glb → respond
```

`store` import를 `store_3dm`, `store_glb`로 교체.

### 5. agent-tools — `pyproject.toml`

`trimesh` 의존성 추가:
```toml
trimesh = ">=4.0,<5"
```

### 6. rag-chat app — `AttachmentDownloadService.java`

`openPreview(AttachmentId, UserId)` 메서드 추가. ownership 확인 로직을 private `resolveOwned()`로 추출하여 `open()`과 공유.

```java
public BlobStoragePort.BlobHandle openPreview(AttachmentId id, UserId caller) {
    Attachment attachment = resolveOwned(id, caller);
    String key = attachment.storageKey();
    if (!key.endsWith(".3dm")) {
        throw ExceptionCreator.of(RagChatErrorCode.PREVIEW_NOT_SUPPORTED).build();
    }
    String glbKey = key.substring(0, key.length() - 4) + ".glb";
    return blobStoragePort.get(glbKey)
            .orElseThrow(() -> ExceptionCreator.of(RagChatErrorCode.ATTACHMENT_NOT_FOUND).build());
}
```

### 7. rag-chat domain — `RagChatErrorCode.java`

에러코드 추가:
```java
PREVIEW_NOT_SUPPORTED(415, "preview not supported for this attachment type"),
```

### 8. rag-chat api — `AttachmentDownloadController.java`

`GET /attachments/{id}/preview` 엔드포인트 추가:

```java
@GetMapping("/{id}/preview")
public Mono<ResponseEntity<byte[]>> preview(
        @PathVariable("id") String id,
        @RequestHeader(value = "X-User-Id", required = false) String xUserId) {
    // ownership 확인 → openPreview() → Content-Type: model/gltf-binary
    // Content-Disposition: inline (다운로드 아님)
}
```

### 9. frontend — `src/shared/lib/model-viewer.d.ts` (신규)

```ts
declare namespace JSX {
  interface IntrinsicElements {
    'model-viewer': React.DetailedHTMLProps<
      React.HTMLAttributes<HTMLElement> & {
        src?: string;
        'camera-controls'?: boolean | string;
        'auto-rotate'?: boolean | string;
        'shadow-intensity'?: string;
        ar?: boolean | string;
      },
      HTMLElement
    >;
  }
}
```

### 10. frontend — `MassingResultCard.tsx`

`@google/model-viewer` side-effect import 추가:

```ts
import '@google/model-viewer';
```

"3D 미리보기" accordion 추가 — `hasDownloadUrl`일 때만 표시, "Program details" 위에 위치:

```tsx
// in-flight 카드: 뷰어 없음 (outputUrl 없으므로)

// result 카드 footer:
footer={
  <div className="flex flex-col gap-xs">
    {hasDownloadUrl && (
      <PreviewAccordion previewUrl={state.toolResult.outputUrl! + '/preview'} />
    )}
    {hasProgram && (
      <ProgramAccordion open={open} onToggle={...} />
    )}
  </div>
}
```

`PreviewAccordion` (MassingResultCard 내부 컴포넌트):

```tsx
function PreviewAccordion({ previewUrl }: { previewUrl: string }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button onClick={() => setOpen(p => !p)} ...>
        {open ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
        <span>3D 미리보기</span>
      </button>
      {open && (
        <model-viewer
          src={previewUrl}
          camera-controls
          auto-rotate
          shadow-intensity="1"
          style={{ width: '100%', height: '240px', borderRadius: '6px', display: 'block' }}
        />
      )}
    </>
  );
}
```

---

## Error Handling

| 상황 | 동작 |
|------|------|
| `store_glb` MinIO 업로드 실패 | 로그 경고 후 워크플로우 계속 — `.3dm`은 정상 저장됨. preview만 불가. |
| `.glb` blob 없음 (레거시 row, 실패 row) | `/preview` → `404` |
| storageKey가 `.3dm` 아닌 타입 | `/preview` → `415 Unsupported Media Type` |
| owner 불일치 | `/preview` → `404` (tenant isolation) |

`store_glb` 실패가 전체 massing 생성을 막지 않도록 워크플로우에서 예외를 catch하고 계속 진행.

---

## Out of Scope

- `.glb` 다운로드 버튼 노출 (`.3dm` 단일 유지)
- `image/*`, `video/*` preview 구현 (엔드포인트 설계만 범용으로, 구현은 해당 milestone에서)
- `.glb` 캐시 무효화 / 재생성 (생성 시점 1회로 충분)
- AR 모드 활성화 (`model-viewer` AR 지원은 mobile에서만 동작 — 현재 scope 밖)
