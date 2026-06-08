package com.playground.docs.api.response;

import com.playground.docs.application.dto.DocumentManifestEntry;
import java.util.List;

/** GET /internal/docs/manifest 응답 (SP3a spec D1). */
public record DocumentManifestResponse(List<Entry> documents) {

    public record Entry(String id, String title) {}

    public static DocumentManifestResponse from(List<DocumentManifestEntry> entries) {
        return new DocumentManifestResponse(
                entries.stream().map(e -> new Entry(e.id().toString(), e.title())).toList());
    }
}
