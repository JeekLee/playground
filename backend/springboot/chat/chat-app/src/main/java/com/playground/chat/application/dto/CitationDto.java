package com.playground.chat.application.dto;

/**
 * Wire-shape citation for both the {@code done} SSE event payload and the
 * message-history response (SP3b spec D4/D5). A flat mirror of the
 * corpus-agnostic {@code SourceRef} four fields plus the marker number
 * {@code n}.
 *
 * <p>{@code n} is the 1-indexed dense citation number that matches the
 * {@code [N]} markers surviving in the assistant's final text. {@code title}/
 * {@code content}/{@code uri} are nullable (Jackson serializes them as JSON
 * null; the frontend treats them as optional — {@code title === null} is the
 * stale heuristic). {@code sourceType} is non-null.
 *
 * <p>Flat mirror (not SourceRef composition) keeps the established flat-DTO
 * convention and avoids {@code @JsonUnwrapped} coupling.
 */
public record CitationDto(int n, String sourceType, String title, String content, String uri) {}
