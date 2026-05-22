"""POST /internal/tools/generate-massing — the M7 ToolDispatcher target
per ADR-08 §A08.11 Exception 4 sub-row + ADR-18 §A18.5 §21.

/internal/** is gateway-internal only (no host port exposure, no cookie
auth). rag-chat reaches it via the compose-internal hostname
http://massing-gen-api:18083/.
"""

from __future__ import annotations

import logging

from fastapi import APIRouter

from ..deps import UserContextDep, WorkflowDep
from ..models import GenerateMassingRequest, GenerateMassingResponse

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/internal/tools", tags=["tools"])


@router.post("/generate-massing", response_model=GenerateMassingResponse)
def generate_massing(
    req: GenerateMassingRequest,
    user: UserContextDep,
    workflow: WorkflowDep,
) -> GenerateMassingResponse:
    logger.info(
        "generate_massing requested",
        extra={"brief_doc_id": str(req.brief_doc_id), "user_id": str(user.user_id)},
    )
    return workflow.run(req, user_id=user.user_id, user_sub=user.user_sub)
