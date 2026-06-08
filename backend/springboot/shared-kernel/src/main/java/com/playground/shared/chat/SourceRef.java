package com.playground.shared.chat;

/**
 * BC-중립 corpus-무관 citation 원자 (SP3b spec D1). 한 소스 참조: 무슨 종류인지
 * ({@code sourceType}), 사람용 라벨({@code title}), 인용된 텍스트({@code content}),
 * 접근 절대 URL({@code uri}). 마커 번호 [N]/position은 포함하지 않는다 — 그것은
 * renumber 기계 + 영속 PK가 소유하는 상태이지 소스의 속성이 아니다.
 *
 * <p>{@code ChatStreamEvent} 옆에 둔다 (BC-중립 wire 계약). 검색 도구(docs-api)가
 * 방출하고 chat 누적기가 해석 없이 복사한다 — 새 corpus(web 등)는 sourceType만
 * 바꿔 같은 형상으로 흘려보낸다.
 */
public record SourceRef(String sourceType, String title, String content, String uri) {
    public SourceRef {
        if (sourceType == null || sourceType.isBlank()) {
            throw new IllegalArgumentException("sourceType must not be blank");
        }
        // title/content/uri는 null·빈 문자열 허용 — 조합 검증 없음.
    }
}
