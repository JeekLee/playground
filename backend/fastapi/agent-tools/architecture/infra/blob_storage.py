"""MinIO upload adapter for the architecture BC (ADR-20 §D3 revised).

agent-tools owns the write path: the `store_3dm` node calls `upload_artifact`
which puts the .3dm bytes into MinIO and returns the object key that
chat's dispatcher will record in `chat.message_attachments`; the `store_glb`
node calls `upload_to_key` to place the preview .glb at the same prefix.

The bucket is shared with chat's download adapter — both services
use `PLAYGROUND_ARCHITECTURE_MINIO_BUCKET` / `PLAYGROUND_CHAT_MINIO_BUCKET`
defaulting to `chat-attachments`, so chat can serve downloads
using the same storageKey.
"""

from __future__ import annotations

import io
import logging
import uuid
from datetime import datetime, timezone

from minio import Minio
from minio.error import S3Error

from shared_kernel.config import Settings

logger = logging.getLogger(__name__)

_client: Minio | None = None
_bucket: str | None = None


def _get_client(settings: Settings) -> tuple[Minio, str]:
    global _client, _bucket
    if _client is None:
        endpoint = settings.minio_endpoint
        # Strip scheme — Minio SDK takes host[:port] only.
        endpoint = endpoint.removeprefix("https://").removeprefix("http://")
        secure = settings.minio_endpoint.startswith("https://")
        _client = Minio(
            endpoint,
            access_key=settings.minio_access_key,
            secret_key=settings.minio_secret_key,
            secure=secure,
        )
        _bucket = settings.minio_bucket
        _ensure_bucket(_client, _bucket)
    return _client, _bucket  # type: ignore[return-value]


def _ensure_bucket(client: Minio, bucket: str) -> None:
    try:
        if not client.bucket_exists(bucket):
            client.make_bucket(bucket)
            logger.info("Created MinIO bucket %s", bucket)
    except S3Error as exc:
        logger.warning("MinIO bucket bootstrap failed (will retry on first upload): %s", exc)


def upload_artifact(
    file_bytes: bytes,
    filename: str,
    content_type: str,
    settings: Settings,
) -> str:
    """Upload bytes to MinIO and return the object key.

    Key format: ``architecture/massing/{uuid}/{filename}``
    so the chat download endpoint can serve it using the stored key.
    """
    client, bucket = _get_client(settings)

    date_prefix = datetime.now(timezone.utc).strftime("%Y%m%d")
    object_key = f"architecture/massing/{date_prefix}/{uuid.uuid4()}/{filename}"

    client.put_object(
        bucket_name=bucket,
        object_name=object_key,
        data=io.BytesIO(file_bytes),
        length=len(file_bytes),
        content_type=content_type or "application/octet-stream",
    )
    logger.info("Uploaded artifact to MinIO key=%s size=%d", object_key, len(file_bytes))
    return object_key


def upload_to_key(
    file_bytes: bytes,
    key: str,
    content_type: str,
    settings: Settings,
) -> None:
    """Upload bytes to an explicit object key (caller owns key derivation).

    Used by store_glb to place the preview .glb at the same prefix as its
    .3dm sibling — extension swapped — so chat's preview endpoint can
    re-derive the key from the stored .3dm storageKey without any new
    Postgres column (design spec 2026-06-05-massing-glb-preview).
    """
    client, bucket = _get_client(settings)
    client.put_object(
        bucket_name=bucket,
        object_name=key,
        data=io.BytesIO(file_bytes),
        length=len(file_bytes),
        content_type=content_type or "application/octet-stream",
    )
    logger.info("Uploaded artifact to MinIO key=%s size=%d", key, len(file_bytes))


def download_from_key(key: str, settings: Settings) -> bytes:
    """Download a MinIO object by its exact key and return the bytes.

    Inverse of ``upload_to_key``. Raises the minio client error (e.g. S3Error
    NoSuchKey) when the object is absent — callers map that to a domain error.
    """
    client, bucket = _get_client(settings)
    resp = client.get_object(bucket_name=bucket, object_name=key)
    try:
        return resp.read()
    finally:
        resp.close()
        resp.release_conn()
