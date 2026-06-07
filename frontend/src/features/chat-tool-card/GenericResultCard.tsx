'use client';

import { Cog } from 'lucide-react';
import type { ToolCardState } from '@/entities/chat';
import { ToolResultCard } from './ToolResultCard';

/**
 * 비등록 도구의 제네릭 결과 카드 (agentic-search spec D3) — 결과 카드가
 * 따로 없는 도구(search_documents 등)의 완료 상태. 도구가 result body에
 * `summary`를 주면 그것을, 없으면 "완료"를 표시. in-flight의 제네릭
 * ToolRunCard와 대칭 — 새 도구는 FE 0줄로 시작~완료가 보인다.
 *
 * 시각 언어는 ToolRunCard와 동일 (Cog 아이콘, 14px semibold 제목). 새
 * 시각 요소 없음 — primaryAction/footer 슬롯은 비운다 (제네릭 도구는
 * 다운로드/액션이 없으므로).
 */
export function GenericResultCard({
  state,
}: {
  state: Extract<ToolCardState, { kind: 'result' }>;
}) {
  const name = state.displayName ?? state.toolCall.name;
  return (
    <ToolResultCard
      ariaLabel={`Tool result: ${name}`}
      icon={<Cog size={18} aria-hidden="true" strokeWidth={1.75} />}
      name={<span className="text-[14px] font-semibold text-text">{name}</span>}
      summary={<span className="font-medium text-text">{state.toolResult.summary ?? '완료'}</span>}
      primaryAction={null}
      footer={null}
    />
  );
}
