# SP3a — Manifest 추출 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `[YOUR DOCUMENTS]` manifest를 docs-api 소유 내부 read 엔드포인트로 옮겨, chat에서 docs 스키마 직접 읽기·doc 메타데이터 모델링·search_path docs 결합을 제거한다.

**Architecture:** docs-api가 `GET /internal/docs/manifest`로 `{id,title}` 경량 리스트를 노출(기존 InternalDocumentController 컨벤션). chat은 JdbcTemplate 직접 SQL 어댑터를 WebClient 어댑터로 교체(포트 무변경)하고, `UserDocumentRef`를 `{ordinal,id,title}`로 슬림화하며 search_path에서 docs를 뺀다.

**Tech Stack:** Spring MVC + Spring Data JPA(docs-api), WebFlux + WebClient(chat), WireMock(chat 테스트), MockMvc(docs 테스트).

**Spec:** `docs/superpowers/specs/2026-06-08-sp3a-manifest-extraction-design.md`

**Worktree:** `EnterWorktree({name:"sp3a-manifest"})` 후 `cp infra/.env .claude/worktrees/sp3a-manifest/infra/.env`. Java 빌드: `backend/springboot`에서 `./gradlew`. 최종 재빌드(Task 3)는 worktree 루트에서 `--env-file infra/.env`.

**구현자 공통 지침:** 각 태스크 코드 블록은 목표 형태다 — 명시된 현재 파일을 먼저 읽고 실제 구조/헬퍼/모듈좌표(settings.gradle)에 맞춰 적응하되, 엔드포인트 계약·시그니처·동작은 그대로. 적응한 부분은 보고.

---

## File Structure

| 영역 | File | Action | 책임 |
|------|------|--------|------|
| docs | `docs-app/.../application/dto/DocumentManifestEntry.java` | Create | `{id,title}` 경량 프로젝션 record |
| docs | `docs-app/.../application/repository/DocumentRepository.java` | Modify | `findManifestByAuthor(AuthorId, int)` 추가 |
| docs | `docs-infra/.../persistence/DocumentJpaRepository.java` | Modify | created_at ASC + limit 프로젝션 쿼리 |
| docs | `docs-infra/.../persistence/DocumentRepositoryImpl.java` | Modify | 위임 |
| docs | `docs-app/.../application/service/DocumentAppService.java` | Modify | `manifestForUser(UUID, int)` |
| docs | `docs-api/.../api/response/DocumentManifestResponse.java` | Create | `{documents:[{id,title}]}` 응답 record |
| docs | `docs-api/.../api/controller/InternalDocumentController.java` | Modify | `GET /internal/docs/manifest` |
| chat | `chat-infra/.../persistence/CrossSchemaUserDocumentManifestAdapter.java` | Delete | docs.documents 직접 SQL 제거 |
| chat | `chat-infra/.../persistence/WebClientUserDocumentManifestAdapter.java` | Create | WebClient로 docs-api 호출 |
| chat | `chat-domain/.../model/UserDocumentRef.java` | Modify | `{ordinal,documentId,title}`로 슬림 |
| chat | `chat-domain/.../service/PromptTemplate.java` | Modify | `[mime, status]` 포맷 제거 |
| chat | `chat-api/.../resources/application.yml` | Modify | search_path에서 docs 제거 |
| docs(문서) | spec / design-doc 노트 | Modify | Task 3 |

---

### Task 1: docs-api — 내부 manifest 엔드포인트 (TDD)

선독: `docs-app/.../application/repository/DocumentRepository.java`, `docs-infra/.../persistence/DocumentJpaRepository.java`(+`DocumentRepositoryImpl.java`, `DocumentJpaEntity.java` — `createdAt`/`title` 필드 확인됨), `docs-app/.../application/service/DocumentAppService.java`(`getBody` 위임 패턴), `docs-api/.../api/controller/InternalDocumentController.java`, `docs-api/.../api/response/DocumentBodyResponse.java`, 그리고 기존 컨트롤러 테스트 스타일(`SearchToolControllerTest`의 MockMvc) + 리포지토리 테스트 하니스 유무.

**Files:**
- Create: `docs-app/src/main/java/com/playground/docs/application/dto/DocumentManifestEntry.java`
- Modify: `docs-app/.../application/repository/DocumentRepository.java`
- Modify: `docs-infra/.../persistence/DocumentJpaRepository.java`, `DocumentRepositoryImpl.java`
- Modify: `docs-app/.../application/service/DocumentAppService.java`
- Create: `docs-api/.../api/response/DocumentManifestResponse.java`
- Modify: `docs-api/.../api/controller/InternalDocumentController.java`
- Test: `docs-api/.../api/controller/InternalDocumentControllerTest.java`(있으면 확장, 없으면 생성), 리포지토리 쿼리 테스트(하니스 따라)

- [ ] **Step 1: 실패 테스트 — 컨트롤러 (MockMvc, 서비스 mock)**

`SearchToolControllerTest`의 MockMvc 스타일을 따라 `InternalDocumentControllerTest`에:

```java
    @Test
    void manifest_returnsIdTitleList() throws Exception {
        UUID u = UUID.randomUUID();
        UUID d1 = UUID.randomUUID();
        when(docService.manifestForUser(eq(u), eq(30)))
                .thenReturn(List.of(new DocumentManifestEntry(d1, "KFI 지침서")));
        mockMvc.perform(get("/internal/docs/manifest")
                        .param("userId", u.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents[0].id").value(d1.toString()))
                .andExpect(jsonPath("$.documents[0].title").value("KFI 지침서"));
    }

    @Test
    void manifest_emptyIsOk() throws Exception {
        UUID u = UUID.randomUUID();
        when(docService.manifestForUser(eq(u), anyInt())).thenReturn(List.of());
        mockMvc.perform(get("/internal/docs/manifest").param("userId", u.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents").isArray())
                .andExpect(jsonPath("$.documents").isEmpty());
    }

    @Test
    void manifest_clampsLimit() throws Exception {
        UUID u = UUID.randomUUID();
        when(docService.manifestForUser(eq(u), eq(100))).thenReturn(List.of());
        mockMvc.perform(get("/internal/docs/manifest")
                        .param("userId", u.toString()).param("limit", "9999"))
                .andExpect(status().isOk());
        // limit 9999 → 100으로 클램프되어 서비스에 전달
    }

    @Test
    void manifest_missingUserId_is400() throws Exception {
        mockMvc.perform(get("/internal/docs/manifest")).andExpect(status().isBadRequest());
    }

    @Test
    void manifest_badUserId_is400() throws Exception {
        mockMvc.perform(get("/internal/docs/manifest").param("userId", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }
```

- [ ] **Step 2: 실패 확인** — `./gradlew :docs:docs-api:test` → 컴파일 실패(타입 없음).

- [ ] **Step 3: 구현**

`DocumentManifestEntry` (docs-app dto — `DocumentBodyDto` 옆):
```java
package com.playground.docs.application.dto;

import java.util.UUID;

/** 프롬프트 manifest용 경량 문서 프로젝션 (SP3a spec D1) — id/title만. */
public record DocumentManifestEntry(UUID id, String title) {}
```

`DocumentRepository`에 추가:
```java
    /**
     * 호출자 소유 문서의 {id,title} 경량 목록 — 업로드 순서(created_at ASC),
     * {@code limit}개. chat의 [YOUR DOCUMENTS] manifest 전용 (SP3a spec D1).
     */
    List<DocumentManifestEntry> findManifestByAuthor(AuthorId author, int limit);
```

`DocumentJpaRepository`에 추가(생성자 표현식 프로젝션 + Pageable 리밋):
```java
    @Query("""
            select new com.playground.docs.application.dto.DocumentManifestEntry(d.id, d.title)
            from DocumentJpaEntity d
            where d.userId = :userId
            order by d.createdAt asc
            """)
    List<DocumentManifestEntry> findManifest(
            @Param("userId") UUID userId, org.springframework.data.domain.Pageable pageable);
```
(import `com.playground.docs.application.dto.DocumentManifestEntry`. docs-infra는 docs-app에 의존하므로 생성자 표현식 대상 가능. `DocumentJpaEntity`의 id는 UUID, title은 String — 생성자 시그니처와 일치함을 확인.)

`DocumentRepositoryImpl`에 추가:
```java
    @Override
    public List<DocumentManifestEntry> findManifestByAuthor(AuthorId author, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return jpaRepository.findManifest(
                author.value(), org.springframework.data.domain.PageRequest.of(0, limit));
    }
```

`DocumentAppService`에 추가(`getBody` 위임 패턴):
```java
    /** chat의 [YOUR DOCUMENTS] manifest용 내부 read (SP3a spec D1). 무인증·visibility-agnostic. */
    @Transactional(readOnly = true)
    public List<DocumentManifestEntry> manifestForUser(UUID userId, int limit) {
        return repository.findManifestByAuthor(AuthorId.of(userId), limit);
    }
```

`DocumentManifestResponse` (docs-api response — `DocumentBodyResponse` 패턴):
```java
package com.playground.docs.api.response;

import com.playground.docs.application.dto.DocumentManifestEntry;
import java.util.List;

/** {@code GET /internal/docs/manifest} 응답 (SP3a spec D1). */
public record DocumentManifestResponse(List<Entry> documents) {

    public record Entry(String id, String title) {}

    public static DocumentManifestResponse from(List<DocumentManifestEntry> entries) {
        return new DocumentManifestResponse(
                entries.stream()
                        .map(e -> new Entry(e.id().toString(), e.title()))
                        .toList());
    }
}
```

`InternalDocumentController`에 추가:
```java
    private static final int DEFAULT_MANIFEST_LIMIT = 30;
    private static final int MAX_MANIFEST_LIMIT = 100;

    @GetMapping("/manifest")
    public ResponseEntity<DocumentManifestResponse> manifest(
            @RequestParam("userId") String userId,
            @RequestParam(value = "limit", defaultValue = "30") int limit) {
        UUID uid;
        try {
            uid = UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            ExceptionCreator.of(DocsErrorCode.USER_HEADER_MISSING).throwIt();
            return null; // unreachable
        }
        int clamped = Math.max(1, Math.min(MAX_MANIFEST_LIMIT, limit));
        return ResponseEntity.ok(
                DocumentManifestResponse.from(docService.manifestForUser(uid, clamped)));
    }
```
(`@RequestParam("userId")` 누락 시 Spring이 400 — `manifest_missingUserId_is400` 충족. 불량 UUID는 400 매핑 에러코드 사용 — `USER_HEADER_MISSING`이 401이면 400짜리 코드(예: SP2에서 추가한 `TOOL_USER_HEADER_MISSING` 또는 신규 `MANIFEST_USER_INVALID`)를 사용. 실제 DocsErrorCode를 읽고 400 매핑 코드 선택·보고. import: RequestParam/RequestMapping은 이미 컨트롤러에 존재.)

- [ ] **Step 4: 리포지토리 쿼리 테스트** — 기존 docs 리포지토리 테스트 하니스(@DataJpaTest 등)를 따라: 같은 user의 문서 3건을 created_at 간격 두고 저장 → `findManifestByAuthor(author, 2)`가 **created_at ASC 앞 2건**을 `{id,title}`로 반환하는지. 하니스가 없으면 이 단계는 컨트롤러+서비스 mock 커버리지로 갈음하고 보고.

- [ ] **Step 5: 통과** — `./gradlew :docs:docs-app:test :docs:docs-api:test :docs:docs-infra:test :docs:docs-api:build` green.

- [ ] **Step 6: Commit** — `feat(docs): internal document manifest endpoint (id/title, created_at ASC)`

---

### Task 2: chat — WebClient manifest 어댑터 + 모델 슬림 + search_path (TDD)

선독: `chat-infra/.../persistence/CrossSchemaUserDocumentManifestAdapter.java`(삭제 대상 — 동작 참고), `chat-app/.../port/UserDocumentManifestPort.java`, `chat-domain/.../model/UserDocumentRef.java`, `chat-domain/.../service/PromptTemplate.java`(assemble의 [YOUR DOCUMENTS] 루프), `chat-infra/.../tool/ToolDispatcherConfig.java`(`toolWebClientBuilder` 빈) + `WebClientToolDispatcher.java`(WebClient 사용·env URL 패턴), `chat-infra/.../tool/WebClientToolDispatcherTest.java`(WireMock 하니스), `chat-app/.../service/ChatTurnService.java`(`recentForUser` 호출부 L248, `DOCUMENT_MANIFEST_LIMIT`=30), `application.yml`. 이 태스크는 record arity 변경이 연쇄되므로 **한 커밋에서 빌드 green을 유지**한다.

**Files:**
- Delete: `chat-infra/.../persistence/CrossSchemaUserDocumentManifestAdapter.java`
- Create: `chat-infra/.../persistence/WebClientUserDocumentManifestAdapter.java`
- Modify: `chat-domain/.../model/UserDocumentRef.java`, `chat-domain/.../service/PromptTemplate.java`
- Modify: `chat-api/.../resources/application.yml`
- Test: `chat-infra/.../persistence/WebClientUserDocumentManifestAdapterTest.java`, 기존 `PromptTemplateTest`

- [ ] **Step 1: UserDocumentRef 슬림화**

```java
public record UserDocumentRef(int ordinal, UUID documentId, String title) {

    public UserDocumentRef {
        Objects.requireNonNull(documentId, "documentId");
        if (ordinal < 1) {
            throw new IllegalArgumentException("ordinal must be >= 1, got " + ordinal);
        }
    }
}
```
(`mimeType`/`extractionStatus` 제거. javadoc의 "type" 언급도 정리 — ordinal/title로 해소한다는 취지로 수정.)

- [ ] **Step 2: PromptTemplate — [mime, status] 제거**

assemble()의 manifest 루프를 다음으로 교체(타입/상태 토막 삭제):
```java
            for (UserDocumentRef d : documents) {
                String title = (d.title() == null || d.title().isBlank()) ? "(untitled)" : d.title();
                sb.append(d.ordinal()).append(". \"").append(title).append("\"")
                  .append(" id=").append(d.documentId()).append('\n');
            }
```
([YOUR DOCUMENTS] 헤더 안내문에 "by type" 언급이 있으면 "by ordinal/title"로 정리.)

- [ ] **Step 3: 실패 테스트 — WebClient 어댑터 (WireMock)**

`WebClientToolDispatcherTest`의 WireMock 하니스를 따라 `WebClientUserDocumentManifestAdapterTest`:
```java
    private WireMockServer wm;
    private WebClientUserDocumentManifestAdapter adapter;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        // base URL을 wm.baseUrl()+"/internal/docs/manifest"로 주입 (테스트 생성자)
        adapter = new WebClientUserDocumentManifestAdapter(
                WebClient.builder(), wm.baseUrl() + "/internal/docs/manifest");
    }

    @AfterEach
    void tearDown() { wm.stop(); }

    @Test
    void mapsDocumentsToRefsInOrderWithOrdinals() {
        UUID u = UUID.randomUUID();
        UUID d1 = UUID.randomUUID(), d2 = UUID.randomUUID();
        wm.stubFor(get(urlPathEqualTo("/internal/docs/manifest"))
                .withQueryParam("userId", equalTo(u.toString()))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"documents\":[{\"id\":\"" + d1 + "\",\"title\":\"A\"},"
                                + "{\"id\":\"" + d2 + "\",\"title\":\"B\"}]}")));
        List<UserDocumentRef> refs = adapter.recentForUser(UserId.of(u), 30);
        assertThat(refs).hasSize(2);
        assertThat(refs.get(0).ordinal()).isEqualTo(1);
        assertThat(refs.get(0).documentId()).isEqualTo(d1);
        assertThat(refs.get(0).title()).isEqualTo("A");
        assertThat(refs.get(1).ordinal()).isEqualTo(2);
    }

    @Test
    void non2xxReturnsEmpty() {
        wm.stubFor(get(urlPathEqualTo("/internal/docs/manifest"))
                .willReturn(aResponse().withStatus(500)));
        assertThat(adapter.recentForUser(UserId.of(UUID.randomUUID()), 30)).isEmpty();
    }

    @Test
    void emptyDocumentsReturnsEmpty() {
        wm.stubFor(get(urlPathEqualTo("/internal/docs/manifest"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"documents\":[]}")));
        assertThat(adapter.recentForUser(UserId.of(UUID.randomUUID()), 30)).isEmpty();
    }

    @Test
    void limitZeroReturnsEmptyWithoutCall() {
        assertThat(adapter.recentForUser(UserId.of(UUID.randomUUID()), 0)).isEmpty();
        wm.verify(0, getRequestedFor(urlPathEqualTo("/internal/docs/manifest")));
    }
```
(`UserId.of(UUID)` 실제 팩토리명 확인. `UserId.value()`가 UUID면 쿼리 param은 `.toString()`.)

- [ ] **Step 4: 실패 확인** — `./gradlew :chat:chat-infra:test` → 컴파일 실패.

- [ ] **Step 5: WebClient 어댑터 구현 + 구 어댑터 삭제**

`CrossSchemaUserDocumentManifestAdapter.java` 삭제. 신규:
```java
package com.playground.chat.infrastructure.persistence;

import com.playground.chat.application.port.UserDocumentManifestPort;
import com.playground.chat.domain.model.UserDocumentRef;
import com.playground.chat.domain.model.id.UserId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * docs-api 내부 manifest 엔드포인트를 호출해 [YOUR DOCUMENTS] 목록을 가져온다
 * (SP3a spec D2). chat은 더 이상 docs 스키마를 직접 읽지 않는다. 실패·타임아웃·
 * non-2xx는 빈 리스트로 degrade → 섹션 생략, 턴 계속(구 JDBC 어댑터와 동일).
 */
@Component
public class WebClientUserDocumentManifestAdapter implements UserDocumentManifestPort {

    private static final Logger log = LoggerFactory.getLogger(WebClientUserDocumentManifestAdapter.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final WebClient webClient;
    private final String endpoint;

    public WebClientUserDocumentManifestAdapter(
            WebClient.Builder builder,
            @Value("${playground.chat.docs-manifest-url:http://docs-api:18082/internal/docs/manifest}")
            String endpoint) {
        this.webClient = builder.build();
        this.endpoint = endpoint;
    }

    private record ManifestResponse(List<Entry> documents) {
        private record Entry(String id, String title) {}
    }

    @Override
    public List<UserDocumentRef> recentForUser(UserId userId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        String uri = UriComponentsBuilder.fromHttpUrl(endpoint)
                .queryParam("userId", userId.value().toString())
                .queryParam("limit", limit)
                .build().toUriString();
        try {
            ManifestResponse resp = webClient.get().uri(uri)
                    .retrieve()
                    .bodyToMono(ManifestResponse.class)
                    .timeout(TIMEOUT)
                    .onErrorResume(e -> {
                        log.warn("manifest fetch failed userId=" + userId.value() + " reason=" + e);
                        return reactor.core.publisher.Mono.empty();
                    })
                    .block();
            if (resp == null || resp.documents() == null) {
                return List.of();
            }
            List<UserDocumentRef> refs = new ArrayList<>(resp.documents().size());
            int ordinal = 1;
            for (ManifestResponse.Entry e : resp.documents()) {
                refs.add(new UserDocumentRef(ordinal++, UUID.fromString(e.id()), e.title()));
            }
            return refs;
        } catch (RuntimeException e) {
            log.warn("manifest fetch error userId=" + userId.value() + " reason=" + e);
            return List.of();
        }
    }
}
```
(테스트용 2-인자 생성자 시그니처 `(WebClient.Builder, String endpoint)`를 그대로 둠 — `@Value` 기본값이 prod 경로. `playground.chat.docs-manifest-url` 키는 ChatProperties가 아니라 직접 @Value — 도구 URL이 env로 오는 관습과 동형. 실제 env override가 필요하면 application.yml/compose에 키 추가는 Task 3에서 확인.)

- [ ] **Step 6: search_path 정리** — `application.yml`:
```yaml
      connection-init-sql: "SET search_path TO chat,identity,public"
```
(주석의 "docs/identity read" 문구도 "identity read"로 수정. docs 스키마 미접근 사실 반영.)

- [ ] **Step 7: 통과 + 게이트** — `./gradlew :chat:chat-domain:test :chat:chat-infra:test :chat:chat-app:test :chat:chat-api:test` green. 기존 `PromptTemplateTest`에 mime/status를 단언하는 케이스가 있으면 슬림 라인으로 갱신. 잔존 grep:
```bash
grep -rn "docs\.documents\|mimeType\|extractionStatus\|CrossSchemaUserDocumentManifest" backend/springboot/chat --include="*.java"
```
→ 0 (identity 어댑터는 docs가 아니므로 무관).

- [ ] **Step 8: Commit** — `feat(chat)!: fetch document manifest via docs-api WebClient; drop docs schema read`

---

### Task 3: 통합 검증 + docs 노트

- [ ] **Step 1: 재빌드** (worktree 루트):
```bash
docker compose -f infra/docker-compose.yml --env-file infra/.env up -d --build docs-api chat-api
```
docs-api·chat-api healthy 대기.

- [ ] **Step 2: manifest 엔드포인트 직접 검증**
```bash
# 데이터 리셋 상태면 문서 시딩 후. 사용자 UUID는 로그인/DB에서 확보.
docker exec agent-tools python -c "
import httpx
r = httpx.get('http://docs-api:18082/internal/docs/manifest',
    params={'userId':'<UUID>','limit':30}, timeout=5)
print(r.status_code, r.text)
"
```
→ `{"documents":[{"id":...,"title":...}]}` (created_at ASC). 문서 없으면 `{"documents":[]}`.

- [ ] **Step 3: chat 부팅 확인** — chat-api 로그에 search_path 관련 에러 없음, 도구 턴에서 manifest 주입 동작(로그/수동). search_path에서 docs 제거됐어도 chat이 docs.* 접근 안 하므로 정상.

- [ ] **Step 4: design-doc 노트** — `docs/design/M4-rag-chat.md`의 agentic-search 노트 아래:
```markdown
> **2026-06-08 — Manifest 추출 (M9 SP3a)**: [YOUR DOCUMENTS] manifest가
> docs-api 내부 엔드포인트(`GET /internal/docs/manifest`, {id,title})로
> 이관됐다. chat은 docs 스키마를 직접 읽지 않으며(search_path에서 docs
> 제거), UserDocumentRef는 {ordinal,id,title}로 슬림화. Spec:
> `docs/superpowers/specs/2026-06-08-sp3a-manifest-extraction-design.md`.
```
spec에 구현 중 deviation 있으면 기록(env 키명 확정값 등).

- [ ] **Step 5: Commit + 수동 E2E 안내** — 사용자: 로그인 → 브리프 업로드 → 문서 언급 도구 턴(매싱/검색)에서 LLM이 manifest로 documentId 해소하는지 확인.

---

## Self-Review

1. **Spec coverage:** D1(엔드포인트·{id,title}·created_at ASC·limit·userId 400)→T1; D2(구 어댑터 삭제·WebClient 신설·포트 무변경·graceful degrade)→T2 Step3-5; D3(UserDocumentRef 슬림·PromptTemplate)→T2 Step1-2; D4(search_path)→T2 Step6; D5 흐름→전체; D6 에러표→T1(400)·T2(degrade); D7 테스트→각 태스크 + 잔존 grep(T2 Step7). Out of Scope(identity·SP3b) 침범 없음.
2. **Placeholder scan:** "DocsErrorCode 400 코드 선택·보고", "리포지토리 테스트 하니스 없으면 갈음", "env 키 application.yml/compose 확인"은 선독+목표코드 동반 의도된 적응. 그 외 TBD 없음.
3. **Type consistency:** `DocumentManifestEntry(UUID id, String title)` T1 전체 일관; `DocumentManifestResponse.from(List<DocumentManifestEntry>)`→Entry(String,String); `findManifestByAuthor(AuthorId,int)`↔`findManifest(UUID,Pageable)`↔`manifestForUser(UUID,int)` 체인 일치; `UserDocumentRef(int,UUID,String)` 슬림 시그니처가 어댑터 생성·PromptTemplate 접근(ordinal/documentId/title)과 일치; `recentForUser(UserId,int)` 포트 무변경.
