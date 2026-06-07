'use client';

import { useMemo } from 'react';
import { TriangleAlert } from 'lucide-react';
import type { ToolCardState } from '@/entities/chat';
import { ToolResultCard } from './ToolResultCard';

/**
 * 비등록 도구의 제네릭 에러 카드 (agentic-search spec D3) — 전용 에러
 * 카드가 없는 도구(search_documents 등)의 실패 상태. MassingErrorCard는
 * M8 전용(parseM8ErrorPrefix·brief 내비게이션 액션)이라 재사용할 수 없어,
 * D3 "제네릭 fallback" 원칙대로 최소 렌더만 한다: warning variant +
 * displayName 제목 + 메시지 본문 + `code: <CODE> · <elapsed>s` footer.
 * 도메인별 secondary action 없음 (제네릭 도구는 재시도 시맨틱이 없다).
 *
 * 시각 언어는 MassingErrorCard와 동일한 ToolResultCard warning 슬롯을
 * 그대로 쓴다 — 새 스타일링 없음.
 */
export function GenericErrorCard({
  state,
}: {
  state: Extract<ToolCardState, { kind: 'error' }>;
}) {
  const { toolError } = state;
  const name = state.displayName ?? state.toolCall.name;
  const elapsedSec = useMemo(() => {
    const ms = Math.max(0, state.resolvedAt - state.calledAt);
    return (ms / 1000).toFixed(1);
  }, [state.calledAt, state.resolvedAt]);

  return (
    <ToolResultCard
      variant="warning"
      ariaLabel={`Tool error: ${name} (${toolError.code})`}
      icon={
        <TriangleAlert size={20} strokeWidth={2.25} aria-hidden="true" className="text-warning" />
      }
      name={<span className="text-[14px] font-semibold text-warning">{name}</span>}
      summary={<span className="font-medium text-text">{toolError.message}</span>}
      primaryAction={null}
      footer={
        <p className="text-[12px] font-medium text-text-muted">
          <span className="font-mono">code: {toolError.code}</span>
          <span aria-hidden="true">{' · '}</span>
          <span>{elapsedSec}s elapsed</span>
        </p>
      }
    />
  );
}
