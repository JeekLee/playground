"""download_from_key — read an object back from MinIO (refine recipe path)."""
from __future__ import annotations

from architecture.infra import blob_storage
from shared_kernel.config import Settings


class _FakeResp:
    def __init__(self, data: bytes):
        self._data = data
        self.closed = False
        self.released = False

    def read(self) -> bytes:
        return self._data

    def close(self) -> None:
        self.closed = True

    def release_conn(self) -> None:
        self.released = True


class _FakeClient:
    def __init__(self, data: bytes):
        self._resp = _FakeResp(data)
        self.calls: list[tuple[str, str]] = []

    def get_object(self, bucket_name, object_name):
        self.calls.append((bucket_name, object_name))
        return self._resp


def test_download_from_key_reads_and_closes(monkeypatch):
    fake = _FakeClient(b"GLBBYTES")
    monkeypatch.setattr(blob_storage, "_get_client", lambda settings: (fake, "playground"))
    out = blob_storage.download_from_key("architecture/massing/x/y.glb", Settings())
    assert out == b"GLBBYTES"
    assert fake.calls == [("playground", "architecture/massing/x/y.glb")]
    assert fake._resp.closed and fake._resp.released
