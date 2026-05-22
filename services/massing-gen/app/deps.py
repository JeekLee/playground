"""FastAPI dependency providers — singletons + per-request user context.

Singletons (docs client, llm client, workflow) are cached on the app
state in main.py; this module exposes Depends() helpers that retrieve
them. UserContext is built per-request from X-User-Id / X-User-Sub
headers per M7 ToolDispatcher's forwarding contract.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Annotated
from uuid import UUID

from fastapi import Depends, Header, Request

from .config import Settings, get_settings
from .docs_client import DocsClient
from .errors import MassingError, MassingErrorCode
from .llm_client import LlmClient
from .workflow import MassingWorkflow


@dataclass(frozen=True, slots=True)
class UserContext:
    user_id: UUID
    user_sub: str | None


def get_user_context(
    x_user_id: Annotated[str | None, Header(alias="X-User-Id")] = None,
    x_user_sub: Annotated[str | None, Header(alias="X-User-Sub")] = None,
) -> UserContext:
    if not x_user_id:
        # M7's ToolDispatcher always sets X-User-Id when forwarding from
        # rag-chat. Missing header is a contract violation.
        raise MassingError(
            MassingErrorCode.BRIEF_NOT_ACCESSIBLE,
            "X-User-Id header missing",
        )
    try:
        user_id = UUID(x_user_id)
    except ValueError:
        raise MassingError(
            MassingErrorCode.BRIEF_NOT_ACCESSIBLE,
            "X-User-Id is not a valid UUID",
        )
    return UserContext(user_id=user_id, user_sub=x_user_sub)


def get_workflow(request: Request) -> MassingWorkflow:
    return request.app.state.workflow


def get_docs_client(request: Request) -> DocsClient:
    return request.app.state.docs_client


SettingsDep = Annotated[Settings, Depends(get_settings)]
UserContextDep = Annotated[UserContext, Depends(get_user_context)]
WorkflowDep = Annotated[MassingWorkflow, Depends(get_workflow)]
DocsClientDep = Annotated[DocsClient, Depends(get_docs_client)]
