"""Architecture-BC dependency wiring.

The generic user-context / settings plumbing lives in
shared_kernel.context; this module wires the BC-specific singletons
(docs client, workflow) that main.py caches on app.state, and re-exports
UserContextDep so routers can pull all their Depends() from one place.
"""

from __future__ import annotations

from typing import Annotated

from fastapi import Depends, Request

from shared_kernel.config import Settings, get_settings
from shared_kernel.context import (
    SettingsDep,
    UserContext,
    UserContextDep,
    get_user_context,
)
from shared_kernel.docs_client import DocsClient

from architecture.app.workflow import MassingWorkflow

__all__ = [
    "Settings",
    "get_settings",
    "UserContext",
    "get_user_context",
    "SettingsDep",
    "UserContextDep",
    "get_workflow",
    "get_docs_client",
    "WorkflowDep",
    "DocsClientDep",
]


def get_workflow(request: Request) -> MassingWorkflow:
    return request.app.state.workflow


def get_docs_client(request: Request) -> DocsClient:
    return request.app.state.docs_client


WorkflowDep = Annotated[MassingWorkflow, Depends(get_workflow)]
DocsClientDep = Annotated[DocsClient, Depends(get_docs_client)]
