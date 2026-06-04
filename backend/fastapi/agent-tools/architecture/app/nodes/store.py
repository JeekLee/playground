"""store node (ADR-20 §D3 revised) — upload .3dm bytes to MinIO.

agent-tools owns the write path: this node uploads the serialized .3dm
to MinIO and stashes the object key in `storage_key` for the `respond`
node to include in the artifact envelope. rag-chat receives the key and
records it in `chat.message_attachments` without touching MinIO itself.
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone

from architecture.app.state import MassingState
from architecture.domain.slug import briefslug
from architecture.infra.blob_storage import upload_artifact
from shared_kernel.config import get_settings

logger = logging.getLogger(__name__)


def store(state: MassingState) -> dict:
    settings = get_settings()

    slug = briefslug(state["detail"].title)
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    filename = f"massing-{slug}-{timestamp}.3dm"

    storage_key = upload_artifact(
        file_bytes=state["file_bytes"],
        filename=filename,
        content_type="application/octet-stream",
        settings=settings,
    )
    logger.info("store node: uploaded %s → %s", filename, storage_key)
    return {"storage_key": storage_key}
