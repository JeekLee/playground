package com.playground.docs.application.dto;

import java.util.UUID;

/** 프롬프트 manifest용 경량 문서 프로젝션 (SP3a spec D1) — id/title만. */
public record DocumentManifestEntry(UUID id, String title) {}
