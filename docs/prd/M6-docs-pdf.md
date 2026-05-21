# PRD: M6 — Docs BC (PDF support)

> **Source of truth:** `docs/superpowers/specs/2026-05-19-post-m5-roadmap.md` §4 (M6 — PDF in docs BC). 이 PRD는 사용자/리뷰어가 읽는 표면이고, 정확한 라이브러리 좌표(PDFBox 패치 버전, Spring AI Vision API 호출 방식), 알고리즘 임계치 튜닝, retry/timeout 정책, prompt 템플릿 같은 기술 컨트랙트는 ADR-16(`docs/adr/16-m6-docs-pdf.md` — 후속 작성 예정)이 우선한다. Section references like "spec §4"는 그 spec 안의 위치를 가리킨다.
>
> **Parallel work:** 이 PRD와 함께 묶이는 per-milestone ADR(**ADR-16**)이 architect의 별도 세션에서 작성된다 — PDFBox 정확한 버전 핀(3.0.x 라인), `PdfExtractorPort` / `VisionOcrPort` 인터페이스 finalize, Spring AI Vision API 호출 시그니처, OCR fallback 임계치 튜닝 가이드, retry/timeout 핀, Flyway migration 파일 이름. Stage-2 design (`docs/design/M6-M8-brief-to-massing.md`)은 이미 land되어 있으며 본 PRD cycle에서 한 가지 amendment만 받았다(meta row에서 `(12 pages)` 제거 — `pdf_page_count` 비도입 결정 반영).
>
> **Decision context:** spec §4의 5개 open question 중 4개가 본 PRD cycle에서 사용자와의 검토로 닫혔다 (PDFBox 채택, Vision LLM hybrid 채택, `mime_type` 컬럼 도입 + `pdf_page_count` 비도입, 원본 PDF 바이트 폐기). 나머지는 ADR-16으로 deferred — 본 문서 끝의 "Open questions for ADR-16" 섹션 참조.

## 한 줄 설명

기존 M2 docs BC가 `.pdf` 업로드를 받아 PDFBox 텍스트 추출 + 페이지별 Vision LLM OCR fallback의 hybrid 파이프라인으로 markdown body를 생성해 저장하고, `(PDF)` 배지로 origin을 노출하며, M3 RAG ingestion 코드 0줄 변경으로 동일한 corpus에 합류시킨다.

## Summary

M6은 M2 docs BC의 **순수 additive 확장**이다. M2가 in-app BlockNote 에디터와 `.md` 업로드 두 경로로 문서를 만들 수 있게 했다면, M6은 세 번째 경로 — `.pdf` 업로드 — 를 같은 `POST /api/docs/upload` 엔드포인트에 추가한다. 차이는 서버 측에서만 발생한다: `.pdf` 업로드는 multipart 수신 → 3단 검증(파일 확장자 + Content-Type + magic bytes) → PDFBox 추출 hybrid 파이프라인 → markdown으로 정규화 → 기존 `body` 컬럼에 저장 → 기존 `docs.document.uploaded` Kafka 이벤트 발행으로 흐른다.

추출 파이프라인의 핵심은 **페이지 단위 hybrid 전략**이다. 각 페이지마다 PDFBox `PDFTextStripper`로 텍스트를 우선 시도하고, 추출된 문자 수가 30자 미만(working threshold — ADR-16이 튜닝)이면 그 페이지를 `PDFRenderer`로 PNG 이미지로 렌더한 뒤 Spring AI ChatClient를 통해 `spark-inference-gateway`의 `qwen3-vl-30b-a3b` Vision 모델에 "이 페이지를 markdown으로 변환해줘" 요청을 보낸다. 페이지별 결과를 순서대로 조립해 단일 markdown body를 만든다. 결과적으로 이미지 전용(스캔) PDF, 한국어 column 흐트러짐, 표/이미지 혼합 PDF 모두 한 경로로 처리된다.

스키마 측면에서는 `docs.documents` 테이블에 `mime_type TEXT NOT NULL DEFAULT 'text/markdown'` 컬럼이 추가된다. `CHECK (mime_type IN ('text/markdown','application/pdf'))` 제약으로 값 도메인을 닫는다. **`pdf_page_count` 컬럼은 도입하지 않는다** — PDF가 markdown으로 변환되고 나면 페이지 개념이 사라져서 표시 외에 의미 있는 사용처가 없기 때문이다(저장 비용보다 가치가 낮음).

원본 PDF 바이트는 **폐기한다** — 추출된 markdown만 body에 저장된다. 이는 M6.1로 deferred된 결정 영역이며 M8 (massing-gen)이 원본 PDF의 binary access를 요구할 경우 M6.1 또는 M8 cycle에서 그때 도입한다. M6 P0의 단순성을 위해 P0는 markdown-only.

지금 출시하는 이유: spec §4가 명시하듯 M6은 brief-to-massing vertical(M6+M7+M8)의 가장 가벼운 입구이며, M8이 brief PDF를 읽기 시작하기 전에 docs BC가 PDF를 받을 수 있어야 한다. 추가로 사용자가 기존에 가진 architecture competition brief PDF들을 즉시 corpus로 흡수할 수 있게 되어 M3 RAG ingestion의 첫 의미 있는 실전 데이터를 확보한다.

## User personas

| 페르소나 | 핵심 동기 | M6에서 가능한 것 |
|---|---|---|
| **Authenticated author (= architect, primary)** | 자신의 competition brief PDF(흔히 스캔 페이지 혼합)를 playground에 흡수해 M3 RAG ingestion + 향후 M8 massing-gen의 input으로 활용 | `/docs/new`의 `+ New document` 드롭다운에서 `↑ Import .md or .pdf…` 선택 → 네이티브 파일 picker(`accept=".md,.pdf"`) → multipart 업로드. 업로드 완료 시 doc detail(`/docs/{id}`)로 redirect되며 `(PDF)` 배지가 title 옆에 inline 렌더되고 markdown body가 BlockNote / SSR 렌더 파이프라인으로 표시됨. |
| **Anonymous reader (secondary)** | 다른 작성자가 공개한 PDF-derived 공개 문서를 읽고 출처를 식별 | `/docs/{id}` 공개 PDF-derived 문서에서 `(PDF)` 배지로 origin을 인식; meta row의 `· source: .pdf` tail로 "이건 originally PDF였구나"를 안다. 본문은 markdown이므로 M2의 reading 파이프라인과 동일하게 렌더된다. |
| **M3 RAG ingestion consumer (downstream BC, indirect)** | M2가 발행한 `docs.document.uploaded` 이벤트를 consume해 chunk + embed | 이벤트 컨트랙트와 body 포맷 둘 다 변경 없음 — body가 markdown이므로 기존 chunker가 PDF-derived 문서와 markdown-authored 문서를 구별 없이 처리. **M3 코드 0줄 변경**이 M6 명시적 invariant. |

## User stories with acceptance criteria

### Authenticated author — upload + happy path

#### Story 1 — `.pdf` 파일을 드롭다운으로 업로드
> As an authenticated architect, I want to upload a competition brief PDF via the `+ New document → Import .md or .pdf…` dropdown so that the same gesture I use for `.md` files works for PDFs.

- [ ] `/docs/mine`의 `+ New document` 버튼 옆 chevron 드롭다운의 row 2 라벨이 `↑ Import .md or .pdf…`로 표시된다 (design doc §2.1).
- [ ] 클릭 시 네이티브 파일 picker가 `accept=".md,.pdf"` 속성으로 열린다 (design doc §2.1).
- [ ] `.pdf` 파일을 선택하면 `POST /api/docs/upload` multipart 호출이 발생한다 (M2 spec §6.1과 같은 엔드포인트).
- [ ] 업로드 성공 시 `/docs/{id}` (M6 frame 78:1552)로 navigate된다.
- [ ] 새 문서는 `visibility='private'`, `path='/'`로 시작한다 (M2 invariant 유지).

#### Story 2 — 텍스트 기반 PDF 추출이 markdown으로 저장됨
> As an authenticated architect uploading a normal text-PDF (architectural brief typically), I want the extracted text to land in `body` as readable markdown so that I can immediately review and (optionally) edit it like any other doc.

- [ ] PDFBox `PDFTextStripper`가 `setSortByPosition(true)`로 호출되어 한글 column 흐트러짐을 완화한다 (의사결정 #8).
- [ ] 페이지 사이 `\f` (form feed) 문자가 `\n\n`로 정규화된다 (의사결정 #8).
- [ ] 추출된 텍스트가 `docs.documents.body` 컬럼에 저장된다 — 별도 새 컬럼 없음.
- [ ] 저장된 row의 `mime_type = 'application/pdf'`이다.
- [ ] M2 1MB body cap이 여전히 적용된다 (1MB 초과 시 413 — 별도 시나리오; PDF 추출 결과가 cap을 넘으면 PDF 자체 size cap을 먼저 trip해야 정상; 시퀀스는 ADR-16).

#### Story 3 — 스캔 PDF는 Vision LLM OCR fallback으로 변환
> As an authenticated architect uploading a scanned (image-only) PDF, I want each page to be OCR'd by a Vision LLM into markdown so that I don't need to OCR upstream or lose the document.

- [ ] PDFBox 추출이 페이지 단위로 호출된다 — 페이지별 문자 수가 측정된다.
- [ ] 페이지당 추출 문자 수 < 30(working threshold — ADR-16 튜닝)이면 그 페이지가 `PDFRenderer`로 PNG 이미지로 렌더된다 (의사결정 #2).
- [ ] PNG가 Spring AI `ChatClient`를 통해 `spark-inference-gateway`의 `qwen3-vl-30b-a3b` Vision 모델에 "이 페이지를 markdown으로 변환해줘" prompt와 함께 전송된다 (의사결정 #2). Exact prompt 템플릿은 ADR-16.
- [ ] Vision LLM의 markdown 응답이 그 페이지의 결과로 채택된다 — PDFBox 결과는 폐기.
- [ ] PDFBox-추출과 Vision-추출 페이지가 원래 페이지 순서대로 조립된 단일 markdown body가 만들어진다.
- [ ] 결과는 `docs.documents.body`에 저장되고 `mime_type='application/pdf'`로 마킹된다.

#### Story 4 — 이미지 전용 PDF의 모든 페이지가 OCR fallback을 trip해도 정상 흐름
> As an authenticated architect uploading a fully scanned PDF (every page is an image), I want the hybrid path to handle it without me toggling any setting — and if the LLM extraction returns nothing per page, I still get a document with an empty body rather than an error.

- [ ] 모든 페이지가 OCR fallback path로 넘어가도 파이프라인이 실패 없이 완료한다 (의사결정 #9).
- [ ] 모든 페이지가 빈 markdown을 반환하면 `body = ''`(빈 문자열)로 저장된다 — 에러 아님.
- [ ] doc detail 페이지가 디자인 doc M6 frame 6의 "This document is empty." empty state를 렌더한다 (design doc §6.2).
- [ ] `(PDF)` 배지는 여전히 표시된다 (mime_type 기준).

#### Story 5 — `(PDF)` 배지가 doc detail에 표시됨
> As any reader (anonymous or authenticated) opening a PDF-derived doc, I want a small `(PDF)` badge inline with the title so that I can tell at a glance the body originated from a PDF rather than authored markdown.

- [ ] `/docs/{id}` 페이지의 title 옆에 `(PDF)` 배지가 inline 렌더된다 (design doc §2.6, §4.4).
- [ ] 배지 스타일: `accent.soft` bg, `accent` fg, `radius.pill`, label `(PDF)` 11/600 (design doc §4.4).
- [ ] 배지는 `mime_type === 'application/pdf'` 조건부 렌더 — markdown-authored 문서에는 표시되지 않는다.
- [ ] meta row에 `· source: .pdf` tail이 추가된다 (design doc §2.6, amendment 후). **`(N pages)` 표기는 포함되지 않는다** (의사결정 #4 — `pdf_page_count` 비도입).
- [ ] doc card list (`/docs`, `/docs/mine`) 에서도 같은 배지가 inline 렌더된다 (design doc §4.4 + open question #4 — 실 구현 PR에 포함).

### Authenticated author — error paths

#### Story 6 — 3단 입력 검증으로 가짜 PDF 거부
> As an authenticated architect, I want the server to refuse a file that pretends to be a PDF but isn't (e.g., a `.pdf`-renamed `.docx`) so that the extraction pipeline doesn't crash mid-way.

- [ ] 업로드된 파일에 대해 세 가지 검증이 모두 통과해야 PDF로 인정된다 (의사결정 #5):
  1. filename 확장자 = `.pdf`
  2. multipart `Content-Type` 헤더 = `application/pdf`
  3. 파일 magic bytes 첫 5바이트 = `%PDF-` (0x25 0x50 0x44 0x46 0x2D)
- [ ] 셋 중 하나라도 실패하면 `400 INVALID_FILE_TYPE`이 반환된다 (의사결정 #6).
- [ ] `INVALID_FILE_TYPE`은 `docs-domain.DocsErrorCode`에 새로 추가되는 enum value다 (의사결정 #6).
- [ ] frontend가 이 400을 받으면 `/docs/new` 또는 `/docs/mine`에서 `danger` 톤 토스트 `Could not read this PDF — try a different file.`을 표시한다 (design doc §6.2).

#### Story 7 — 손상된 PDF 거부
> As an authenticated architect uploading a corrupted PDF, I want a clear 400 with an identifiable error code rather than a 500 so that I know to retry with a different file rather than wait for the operator to investigate.

- [ ] PDFBox가 IOException을 throw하면 `400 PDF_CORRUPTED`가 반환된다 (의사결정 #6).
- [ ] `PDF_CORRUPTED`는 `DocsErrorCode`에 새로 추가되는 enum value다 (의사결정 #6).
- [ ] 응답 body에 user-facing 메시지가 포함된다 (정확한 wording은 ADR-16; design doc §6.2의 toast copy와 일관).

#### Story 8 — 암호화된 PDF 거부
> As an authenticated architect uploading a password-protected PDF, I want a distinct error code so that I know I need to decrypt the PDF first (vs the corrupt case).

- [ ] PDFBox가 `InvalidPasswordException`을 throw하면 `400 PDF_ENCRYPTED`가 반환된다 (의사결정 #6).
- [ ] `PDF_ENCRYPTED`는 `DocsErrorCode`에 새로 추가되는 enum value다 (의사결정 #6).
- [ ] M6 P0는 암호 입력 UI를 제공하지 않는다 — 사용자가 미리 복호화한 PDF를 업로드해야 한다.

#### Story 9 — 페이지 수 / 파일 크기 캡
> As the operator, I want hard caps on PDF size and page count so that one giant PDF doesn't tie up the Vision LLM for hours and burn GPU minutes.

- [ ] 일반 PDF: **25MB** size cap, **200 pages** page cap 적용 (의사결정 #3).
- [ ] OCR fallback이 한 페이지라도 발동된 PDF는 전체 page cap이 **30 pages**로 좁혀진다 (의사결정 #3 — 느리니까 보수적).
- [ ] page cap 초과 시 `413 PDF_TOO_MANY_PAGES` 반환 (의사결정 #6).
- [ ] size cap 초과 시 `413 FILE_TOO_LARGE` 반환 (의사결정 #6 — 기존 multipart cap이 있으면 그것 재사용).
- [ ] page cap이 OCR-fallback-trigger로 인해 30 pages로 낮아지는 사실은 추출 도중에 발견될 수 있으므로 ADR-16이 정확한 순서를 핀 (text-only pass에서 200까지 처리 → 첫 OCR-fallback 페이지 만나면 즉시 abort + 30 page 룰로 재평가; 또는 페이지 수를 먼저 측정 후 sample-based 결정 — ADR 영역).

### Anonymous reader — read

#### Story 10 — 공개 PDF-derived 문서를 익명으로 읽기
> As an anonymous reader following a public link to a PDF-derived doc, I want it to render identically to a markdown-authored doc except for the `(PDF)` badge so that the reading experience is consistent.

- [ ] `/docs/{id}` (visibility=public) PDF-derived 문서를 익명으로 접근 시 200 + body 렌더 (M2 spec §6.1 `Auth: optional`).
- [ ] body는 M2의 reading 파이프라인(`unified` + `remark-gfm` + `rehype-sanitize` + `shiki`)으로 렌더된다 — M6 변경 없음.
- [ ] `(PDF)` 배지와 `· source: .pdf` meta tail이 표시된다.
- [ ] OpenGraph 메타 태그(M2 spec §7.4)는 M6 변경 없이 그대로 방출된다.

### Cross-milestone — M3 regression invariant

#### Story 11 — M3 RAG ingestion이 PDF-derived 문서도 변경 없이 처리
> As the M3 RAG-ingestion BC (downstream consumer), I want PDF-derived docs to flow through my existing chunker + embedder + pgvector store untouched so that no M3 code change is required.

- [ ] PDF-derived 문서 업로드 시 `docs.document.uploaded` Kafka 이벤트가 publish된다 (M2 spec §5 envelope 동일).
- [ ] 이벤트 payload는 M2와 동일한 shape — `mime_type` 같은 신규 필드는 envelope에 추가되지 않는다 (M3가 신경 쓰지 않음).
- [ ] M3가 이벤트를 consume → docs body fetch (M2 spec §11 Q2 메커니즘 — `/internal/docs/{id}/body` HTTP) → chunker로 분할 → BGE-M3 임베딩 → pgvector store 순서가 markdown-authored 문서와 정확히 동일하게 흐른다.
- [ ] M3 코드(`backend/rag-ingestion/*`)가 본 PRD cycle에서 한 줄도 변경되지 않는다 — invariant.
- [ ] 통합 테스트: PDF 업로드 → M3 ingestion 완료 → pgvector에 해당 문서의 chunk가 query 가능함을 단언.

## UX surfaces

Per design doc `docs/design/M6-M8-brief-to-massing.md` (§2.1, §2.6 — 본 PRD cycle의 amendment 후).

| Route | Auth | M6 변경 |
|---|---|---|
| `/docs/mine` | required | `+ New document` 드롭다운의 row 2 label이 `↑ Import .md or .pdf…`로 변경. 그 외 M2 verbatim. |
| `/docs/new` | required | M2의 BlockNote 에디터 (변경 없음). PDF 업로드는 `/docs/mine` 드롭다운 → 파일 picker 경로로만; `/docs/new` 자체는 PDF를 받지 않는다 (in-app authoring surface). |
| `/docs/{id}` | optional | PDF-derived 문서일 때 `(PDF)` 배지 inline + meta row `· source: .pdf` tail. body는 markdown이므로 reading 파이프라인 동일. |
| `/docs` | optional | doc card list에 `(PDF)` 배지 inline (design doc open question #4 — 실 구현 PR이 포함). |

| Endpoint | Auth | M6 변경 |
|---|---|---|
| `POST /api/docs/upload` | required | multipart `Content-Type: application/pdf` 수용. 3단 검증 + PDFBox hybrid 파이프라인. 응답은 기존 `DocDetail` shape — `mime_type` 필드 추가 가능(implementer 결정 / ADR-16). |
| `GET /api/docs/{id}` | optional | 응답 DTO에 `mime_type` 필드 추가(frontend가 배지 렌더에 사용). 그 외 M2 verbatim. |

신규 라우트는 없다 — M6은 M2의 기존 surface 위에 hybrid 추출과 mime_type만 더한다.

## Bounded Context: Docs (M6 amendment)

M2의 Docs BC를 그대로 사용한다 — 새 BC를 만들지 않는다. M6은 그 안에 두 가지 adapter와 한 가지 schema 변경을 추가한다.

- **책임 (Responsibility, M2와 동일):** 다중 작성자 문서의 owning context. M6은 이 정의를 변경하지 않는다.
- **새 도메인 port (in `docs-domain`, Spring-free):**
  - `PdfExtractorPort` — `extract(byte[] pdfBytes): ExtractedDocument` (페이지 리스트 + 메타). 정확한 시그니처는 ADR-16.
  - `VisionOcrPort` — `extractPageAsMarkdown(byte[] pngBytes, int pageIndex): String`. 정확한 시그니처는 ADR-16.
- **새 인프라 adapter (in `docs-infra`):**
  - `PdfBoxExtractorAdapter implements PdfExtractorPort` — Apache PDFBox 3.0.x 호출 (의사결정 #1). `setSortByPosition(true)`, `\f` → `\n\n` 정규화 (의사결정 #8). 페이지 단위 hybrid 결정도 여기서.
  - `VisionOcrAdapter implements VisionOcrPort` — Spring AI ChatClient → `spark-inference-gateway`의 `qwen3-vl-30b-a3b`. 정확한 호출 형태(ChatClient 직접 vs 별도 prompt template)는 ADR-16 (의사결정 #2).
- **위치 invariant:** 두 adapter 모두 **`docs-infra`**에 위치한다. `docs-domain`은 Spring-free 유지 (의사결정 #10).
- **소유 데이터 (M2와 동일, 한 컬럼 추가):** `docs.documents` 테이블에 새 컬럼:
  ```sql
  ALTER TABLE docs.documents
    ADD COLUMN mime_type TEXT NOT NULL DEFAULT 'text/markdown'
      CHECK (mime_type IN ('text/markdown','application/pdf'));
  ```
  Flyway migration 파일 이름(`V20260520xxxx__add_mime_type.sql` 류)은 ADR-16. **`pdf_page_count` 컬럼은 도입하지 않는다** (의사결정 #4).
- **이벤트 (M2와 동일):** `docs.document.uploaded` / `visibility-changed` / `deleted`. envelope shape, payload shape 모두 변경 없음 — M3가 신경 쓰지 않도록 invariant 유지 (의사결정 #12).
- **새 에러 코드 (in `docs-domain.DocsErrorCode`, 의사결정 #6):**
  - `INVALID_FILE_TYPE` (400) — 3단 검증 중 하나라도 실패
  - `PDF_CORRUPTED` (400) — PDFBox IOException
  - `PDF_ENCRYPTED` (400) — PDFBox InvalidPasswordException
  - `PDF_TOO_MANY_PAGES` (413) — 페이지 수 cap 초과
  - `FILE_TOO_LARGE` (413) — multipart size cap 초과 (기존 코드 재사용 가능 — ADR-16 확인)
- **외부 의존성 (신규):** `spark-inference-gateway` (Vision OCR fallback path). 호출은 M3/M4가 이미 사용하는 동일한 게이트웨이를 경유한다 — ADR-04 영역 (ADR-08은 변경 없음 — 의사결정 #11).

## Non-functional requirements

- **`docs-domain`는 Spring-free 유지** (M2 invariant). PdfBox / Spring AI import는 `docs-infra`에만 존재 (의사결정 #10).
- **M3 regression invariant**: M3 BC 코드 0줄 변경 (의사결정 #12, Story 11).
- **ADR-08 invariant**: M6은 ADR-08(BC-to-BC 통신)을 amend하지 않는다 — Vision LLM 호출은 spark-inference-gateway 경유로 ADR-04 영역 (의사결정 #11). ADR-16은 이 invariant를 explicit하게 명시한다.
- **Body size cap (M2 §10에서 유지)**: 1MB raw markdown. PDF 추출 결과가 1MB를 초과하면 body 저장이 실패하지 않도록 PDF size cap(25MB) + page cap(200 / 30 with OCR)이 먼저 trip되도록 ADR-16이 시퀀스를 핀. Worst case에 추출 결과가 1MB cap을 위반하면 어떻게 대응할지(전체 거부 vs truncate)는 ADR-16 open question.
- **OCR fallback latency**: Vision LLM 호출은 페이지당 수 초 단위가 예상되므로 30 페이지 PDF는 1분 이상 걸릴 수 있다. multipart 업로드 응답을 sync로 기다리는 P0 UX의 한계 — async 큐 + 폴링은 M6.1 (out of scope). ADR-16이 multipart timeout + Vision retry policy를 핀.
- **3단 검증 정합성**: 셋 모두 통과해야 PDF 분기로 진입; 한국어 / 영어 multipart 인코딩에 따른 `Content-Type` 헤더 변형은 ADR-16이 명시.
- **에러 코드 enumeration**: 5개의 새 에러 코드가 `docs-domain.DocsErrorCode`에 추가된다 (의사결정 #6). HTTP 상태 매핑 (400 / 413)은 PRD에서 핀; 정확한 i18n 메시지는 ADR-16 / implementer.
- **Vision LLM 모델 swap 호환성**: 현재 `spark-inference-gateway`에 `qwen3-vl-30b-a3b`가 다운로드 중. 다운 완료 후 텍스트 `qwen3-30b-a3b`를 swap해서 단일 vLLM 인스턴스가 텍스트+vision 둘 다 cover한다 (의사결정 #2). M6 출시 직전까지 swap이 완료되어야 한다. swap 일정과 fallback 전략(다운로드 미완 시 M6 ship blocker인지)은 ADR-16 / 운영 결정 영역.
- **원본 PDF 폐기 invariant**: 추출 완료 후 원본 PDF 바이트는 buffer에서만 존재하고 영구 저장되지 않는다 (의사결정 #7). M6.1이 binary storage를 도입할 때까지 이 invariant 유지.
- **검색 (M2 §10 ingest로 유지)**: M2의 OpenSearch projection은 `docs.documents.body`를 인덱스에 반영. PDF-derived markdown body가 정확히 같은 경로로 인덱싱된다 — M6 변경 없음.
- **Observability**: PDF 업로드 시 (a) PDFBox 추출에 걸린 시간, (b) OCR fallback이 발동된 페이지 수, (c) Vision LLM 호출 횟수와 누적 latency 가 INFO 구조화 로그로 기록된다. 정확한 필드는 ADR-16.

## Acceptance criteria (end-to-end)

마일스톤 클로즈 기준 — 사용자 시나리오와 기술 검증 둘 다 포함.

### User-facing scenarios

- [ ] **Happy text-PDF**: architect가 brief.pdf(텍스트 기반)를 `/docs/new` 드롭다운으로 업로드하면 doc detail이 markdown으로 렌더링되고 `(PDF)` 배지가 title 옆에 inline으로 보인다.
- [ ] **Scanned PDF**: architect가 모든 페이지가 image인 PDF를 업로드하면 Vision LLM fallback이 발동되어 페이지별로 markdown으로 변환된다. 결과 doc detail의 body가 비어있지 않다 (Vision 응답이 정상이면).
- [ ] **Empty extraction**: 모든 페이지의 Vision LLM 응답이 빈 markdown이면 body=''로 저장되고 doc detail이 "This document is empty." empty state를 렌더 (Story 4).
- [ ] **Encrypted PDF**: 암호화된 PDF 업로드 → 400 `PDF_ENCRYPTED` (Story 8).
- [ ] **Corrupted PDF**: 손상된 PDF 업로드 → 400 `PDF_CORRUPTED` (Story 7).
- [ ] **25MB 초과**: 25MB가 넘는 PDF 업로드 → 413 `FILE_TOO_LARGE` (Story 9).
- [ ] **201 페이지**: 201 페이지 PDF 업로드 → 413 `PDF_TOO_MANY_PAGES` (Story 9, 일반 PDF cap).
- [ ] **OCR fallback + 31 페이지**: OCR fallback이 한 페이지 이상 발동되는 31 페이지 PDF 업로드 → 413 `PDF_TOO_MANY_PAGES` (Story 9, OCR-trigger cap 30).
- [ ] **Mislabeled file**: 확장자만 `.pdf`이고 내용은 다른 binary인 파일 업로드 → 400 `INVALID_FILE_TYPE` (Story 6).
- [ ] **Frontend file picker**: `/docs/new` 드롭다운 → `↑ Import .md or .pdf…` 클릭 → 네이티브 파일 picker가 `.md`와 `.pdf` 둘 다 accept (design doc §2.1).
- [ ] **Badge on doc detail**: `mime_type='application/pdf'` 문서의 `/docs/{id}` 페이지가 `(PDF)` 배지를 title 옆에 inline 렌더 (design doc §2.6, §4.4). 같은 페이지가 markdown 문서에는 배지를 표시하지 않는다.
- [ ] **Meta row tail**: PDF 문서의 meta row가 `· source: .pdf`로 끝난다 — `(N pages)` 표기 없음 (design doc §2.6 amendment).

### Technical validation

- [ ] `docs.documents.mime_type` 컬럼이 Flyway migration으로 추가되며 `CHECK (mime_type IN ('text/markdown','application/pdf'))` 제약을 가진다.
- [ ] 기존 row들은 default `'text/markdown'`로 backfill된다 (DEFAULT 표현으로 자동 — migration이 명시적 UPDATE를 요구하지 않는다).
- [ ] `docs.documents`에 `pdf_page_count` 컬럼이 **존재하지 않는다** — schema migration이 이 컬럼을 추가하지 않는 것을 단언 (의사결정 #4).
- [ ] `PdfExtractorPort` / `VisionOcrPort` 인터페이스가 `docs-domain`에 정의되며 Spring annotation을 사용하지 않는다 (의사결정 #10).
- [ ] `PdfBoxExtractorAdapter` / `VisionOcrAdapter` 구현이 `docs-infra`에 위치한다 (의사결정 #10).
- [ ] 5개의 새 에러 코드(`INVALID_FILE_TYPE`, `PDF_CORRUPTED`, `PDF_ENCRYPTED`, `PDF_TOO_MANY_PAGES`, `FILE_TOO_LARGE`)가 `DocsErrorCode`에 추가된다 (의사결정 #6).
- [ ] 3단 검증(filename ext + Content-Type + magic bytes)이 `docs-app` 또는 `docs-api`에 구현되며 모든 시나리오가 통합 테스트로 검증된다 (의사결정 #5).
- [ ] PDFBox `setSortByPosition(true)`와 `\f` → `\n\n` 정규화가 PdfBoxExtractorAdapter에 wired (의사결정 #8).
- [ ] Vision OCR fallback 임계치 = 페이지당 추출 문자 수 < 30 (working — ADR-16 튜닝). 통합 테스트가 임계치 동작을 확인.
- [ ] 원본 PDF 바이트가 영구 저장되지 않는다 — 어떤 새 BYTEA 컬럼도, 어떤 새 storage 컨테이너도 추가되지 않음을 단언 (의사결정 #7).

### M3 regression (invariant)

- [ ] M3 BC(`backend/rag-ingestion/*`) 코드가 본 PRD cycle PR set에서 한 줄도 변경되지 않는다.
- [ ] PDF 업로드 → M3 ingestion → pgvector 인덱스 entry 확인의 end-to-end 통합 테스트가 통과한다.
- [ ] `docs.document.uploaded` 이벤트 envelope/payload shape이 변경되지 않는다 — wire-level snapshot 테스트 통과 (의사결정 #12).

### ADR-08 invariant

- [ ] ADR-08(`docs/adr/08-inter-service-comms.md`) 파일이 본 PRD cycle PR set에서 변경되지 않는다 (의사결정 #11). ADR-16 작성 시 이 invariant를 explicit하게 기술한다.

### Design doc invariant

- [ ] `docs/design/M6-M8-brief-to-massing.md` §2.6의 `(12 pages)` 표기가 모두 삭제되었다 — 본 PRD PR이 design doc amendment를 함께 운반한다.

### Cross-milestone (traceability — non-blocking for M6 close)

- [ ] `docs/roadmap.md` §M6의 acceptance bullet들과 본 PRD 사이에 모순이 없다 — 만약 모순이 있다면 PRD가 spec / 사용자 결정을 따른다 (PRD는 spec §4의 사용자 결정 12개를 명문화한 것).
- [ ] ADR-16(`docs/adr/16-m6-docs-pdf.md`)이 본 PRD 다음 단계로 작성되며 PDFBox 정확한 버전, Vision API 호출 시그니처, OCR 임계치 튜닝, retry/timeout, Flyway 파일 이름 등을 핀.

## Out of scope

### M6.1 (same milestone bucket, ship if cycle has slack)

- **원본 PDF 바이트 영구 저장.** P0는 추출 후 폐기. M8 (massing-gen)이 원본 PDF binary access를 요구하면 그 시점에 결정 — `docs.documents.pdf_bytes BYTEA` 컬럼 추가 vs 별도 storage 컨테이너(MinIO 등) (의사결정 #7).
- **Async 업로드 큐 + 진행률 폴링 UX.** 30 페이지 OCR fallback이 1분 이상 걸리는 P0 UX 한계 해소. 업로드 시 즉시 doc id 반환 → 백그라운드 추출 진행 → 폴링/SSE로 완료 통보.
- **암호화 PDF 풀기 UI.** M6 P0는 `PDF_ENCRYPTED`로 즉시 거부. M6.1에서 frontend가 password 입력 모달을 추가하고 백엔드가 PDFBox `setPassword()` 경로를 지원할 수 있음.
- **Layout-aware extraction (tables, columns).** P0의 PDFBox `setSortByPosition(true)`만으로 한국어 column 흐트러짐을 완화하지만, 표나 복잡한 layout은 여전히 손실. 전용 layout-aware library 또는 Vision LLM-only 경로 사용 (cost trade-off 평가).
- **doc detail에서 원본 PDF 다운로드 link.** 원본을 보존하지 않으므로 P0에 없음. M6.1에서 (영구 저장 결정과 함께) 도입.
- **mime_type별 별도 OpenSearch 인덱스 / 검색 필터.** P0는 모든 markdown body를 단일 인덱스에 합치고 검색은 mime_type을 구별하지 않는다. M6.1+에서 `mime_type=application/pdf` 필터를 검색 UI에 노출 가능.

### P2 (별도 후속 마일스톤)

- **`.docx`, `.pptx`, 기타 binary 포맷.** M6은 PDF만; 다른 포맷은 별도 마일스톤. PDFBox + Vision hybrid 패턴은 재사용 가능하지만 각 포맷의 추출 라이브러리는 별도.
- **PDF 안의 이미지 자체 추출 / 별도 첨부 surface.** P0는 페이지를 통째로 렌더해서 Vision LLM이 markdown으로 한 번에 변환하므로 이미지가 markdown 안에 `![]()` 형태로 들어가지는 않는다. 이미지 첨부는 M2.1의 image upload feature와 함께 다뤄질 영역.
- **OCR 결과 사용자 검토 / 수정 흐름.** Vision LLM 오류를 사용자가 BlockNote 에디터에서 수정하는 path는 이미 가능 (markdown이라서). 별도 review workflow는 P2.
- **Vision LLM 비용 budget / quota / per-user rate limit.** P0는 호출 횟수 제한 없음 (personal scale). 다중 사용자 / 외부 트래픽이 늘면 도입.

## Dependencies

- **요구:** M0 — compose stack (Postgres + Redis + Kafka), spark-inference-gateway가 host process로 동작 중 (`host.docker.internal:10080`).
- **요구:** M1 (Identity) — `X-User-Id` 헤더 인젝션 (M2 invariant 유지).
- **요구:** M2 (Docs) — 출시 완료. M6은 M2의 `docs` BC quadruplet 위에 add-on이다. M2의 OpenSearch projection, `docs.document.*` 이벤트, BlockNote 에디터, doc detail 페이지 모두 M2 PR에서 land된 상태여야 한다.
- **요구:** M3 (RAG-Ingestion) — 출시 완료. M6은 M3 코드를 변경하지 않지만 PDF-derived 문서가 M3를 통과해서 pgvector에 들어가는 것을 acceptance로 단언하므로 M3가 동작 중이어야 한다.
- **요구:** `spark-inference-gateway`에 **`qwen3-vl-30b-a3b` 모델이 로드되어 있어야 한다**. 현재 다운로드 중 (의사결정 #2) — M6 ship 전 swap 완료가 운영 prerequisite. 다운 미완 시 M6 ship blocker 여부는 ADR-16 / 운영 결정.
- **요구:** ADR-16 (`docs/adr/16-m6-docs-pdf.md` — 후속) — PDFBox 정확한 버전 핀, `PdfExtractorPort` / `VisionOcrPort` 인터페이스 시그니처, Spring AI Vision API 호출 방식(ChatClient 직접 vs 별도 prompt builder), Vision prompt 템플릿, OCR 임계치(30자) 튜닝 데이터 부재 시의 결정 근거, multipart timeout / Vision retry 정책, Flyway migration 파일 이름, ADR-08 amend 없음 invariant를 명시.
- **신규 외부 의존성 (이번 마일스톤에서 도입):** Apache PDFBox 3.0.x — `docs-infra`의 Gradle dependency로만 추가. 새 컨테이너 / 외부 서비스 도입 없음 (Vision LLM은 기존 spark-inference-gateway 경유).
- **소비자 (M6 close blocker 아님):** M3 RAG-Ingestion (변경 없음 — invariant), 향후 M8 massing-gen (M6.1에서 binary storage 결정과 함께).

## Open questions for ADR-16

ADR-16(architect)이 해소할 사항. 본 PRD가 사용자와의 검토로 닫은 결정은 이미 위 본문에 명문화되어 있으므로 여기 남는 것은 라이브러리/알고리즘/정책 핀이다.

1. **Apache PDFBox 정확한 패치 버전** — 3.0.x 라인 중 어느 버전(예: 3.0.3 vs latest). Gradle BOM / dependency-management 처리.
2. **`PdfExtractorPort` / `VisionOcrPort` 인터페이스 정확한 시그니처** — 반환 타입(페이지 리스트 vs Stream vs Flux), 페이지별 메타데이터 캐리 여부, 에러 표현(Exception vs Result type).
3. **Spring AI Vision API 호출 방식** — `ChatClient.create(...).prompt().user(...).call()` 직접 vs별도 `VisionPromptTemplate` 추상화 vs 더 낮은 레벨 API. Spring AI 1.0의 Vision 기능 안정성과 함께 평가.
4. **Vision prompt 템플릿** — "이 페이지를 markdown으로 변환해줘"의 정확한 wording, 시스템 프롬프트 유무, 한국어/영어 mix 처리. 튜닝 corpus 부재 시 working draft + M6.1에서 evidence-based refine.
5. **OCR fallback 임계치 (현재 30자)** — 튜닝 데이터 부재 시 결정 근거. 임계치를 너무 낮추면 정상 추출된 빈약한 페이지를 OCR로 재처리(비용 ↑), 너무 높이면 텍스트가 일부 있지만 layout이 깨진 페이지를 PDFBox 결과로 채택(품질 ↓). 30자가 working default이며 평가 metric은 ADR-16 sketch.
6. **Multipart upload timeout** — Vision OCR fallback path가 30 페이지를 30초씩 처리하면 15분이 걸린다. gateway 측 timeout + Spring Boot multipart timeout + spark-inference-gateway HTTP timeout 3개의 정합성 필요.
7. **Vision LLM 호출 retry / circuit breaker 정책** — 페이지 단일 실패 시 retry? skip? entire upload 실패? Resilience4j 적용 여부.
8. **Vision LLM 호출 동시성** — 30 페이지를 직렬로 처리하면 너무 느림. parallel 처리 시 concurrency limit (spark-inference-gateway의 vLLM 처리량과 trade-off). 정확한 fan-out 정도.
9. **Flyway migration 파일 이름** — `V2026052Xxxxx__add_mime_type.sql` 정확한 timestamp + 순번.
10. **PDF page cap 측정 시점** — 200 / 30 page cap을 (a) 추출 시작 전 미리 page count 측정 후 거부 vs (b) text-only pass 진행 중 첫 OCR-fallback 페이지 만나면 abort 후 재평가. ADR-16이 명확한 시퀀스를 핀.
11. **1MB body cap과의 충돌 해결** — Vision LLM이 페이지당 50KB markdown을 반환하면 30 페이지 = 1.5MB로 body cap 초과 가능. (a) 전체 거부 vs (b) truncate + 사용자 통지 vs (c) cap을 올림. P0 working: 전체 거부 + 명시적 에러 — ADR-16 확인.
12. **`mime_type` 응답 노출 위치** — `GET /api/docs/{id}` 응답 DTO의 어느 필드(`mimeType` camelCase). `GET /api/docs` 리스트 응답에도 포함할지(frontend가 card list 배지 렌더에 필요).
13. **OpenSearch indexer가 mime_type을 인덱스 필드로 갖는지** — P0는 검색에 mime_type 필터를 노출하지 않지만(M6.1+), 인덱스 시점에 필드를 같이 색인하면 미래 cost 낮음. ADR-16 결정.
14. **`spark-inference-gateway` 모델 swap 일정 / fallback** — `qwen3-vl-30b-a3b` 다운로드 미완 상태로 M6를 ship할 수 없다면 어떻게 graceful하게 OCR fallback path를 비활성화할지 (feature flag? 모든 PDF를 PDFBox-only로 시도?). 운영 + ADR 경계.
15. **3단 검증의 Content-Type 헤더 변형 허용 목록** — `application/pdf` 외에 일부 브라우저/OS가 보낼 수 있는 변형(`application/x-pdf`, `application/acrobat` 등) 수용 여부. 보수적으로 `application/pdf`만 허용하는 것이 working — ADR-16 핀.
16. **에러 응답 body shape** — M2 spec의 기존 에러 응답 포맷 재사용 (code + message + 옵션 details). i18n 메시지의 ko / en 핸들링.

---

> **PRD vs ADR:** 이 문서는 사용자(authenticated architect / anonymous reader)와 리뷰어가 읽는 표면이다. PDFBox 패치 버전, Vision API 호출 시그니처, OCR 임계치 튜닝, retry/timeout/concurrency 정확한 수치, Flyway migration 이름 같은 기술 컨트랙트는 ADR-16(architect의 per-milestone ADR)이 우선한다. PRD가 ADR과 어긋나 보이면 ADR을 따른다.
>
> **PRD vs spec:** spec(`docs/superpowers/specs/2026-05-19-post-m5-roadmap.md`) §4의 5개 open question 중 4개는 본 PRD가 사용자와의 검토로 닫았다 — PDFBox 채택 (#1), Vision LLM hybrid (#2), `mime_type` 컬럼 도입 + `pdf_page_count` 비도입 (#3, #4 합쳐), 원본 PDF 폐기 (#5의 P0). 나머지 라이브러리 핀 / 알고리즘 임계치 / retry 정책은 ADR-16. spec과 본 PRD가 어긋나 보이면 본 PRD가 (사용자 검토를 거친 후속 문서이므로) 우선한다.
>
> **PRD vs design doc:** `docs/design/M6-M8-brief-to-massing.md`의 M6 frame 78:1531 (dropdown row label) + M6 frame 78:1552 (doc detail with `(PDF)` badge)은 본 PRD의 surface 결정과 정합한다. 본 PRD cycle이 design doc §2.6의 `(12 pages)` 표기를 제거하는 amendment를 함께 운반한다 — `pdf_page_count` 비도입 결정에 따라.
