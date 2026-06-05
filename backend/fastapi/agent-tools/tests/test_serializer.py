"""`.3dm` serializer tests per ADR-18 §A18.2.

The headline M8 acceptance bullet is "Generated `.3dm` opens in Rhino 7+
without errors". rhino3dm's own reader (`File3dm.FromByteArray`) is the
proxy for that: if rhino3dm cannot parse the bytes back, neither can
Rhino. So every test here round-trips the serializer output through the
reader instead of trusting byte counts.
"""

from __future__ import annotations

import rhino3dm

from architecture.domain.models import RoomBox
from architecture.infra.serializer import serialize_massing


def _boxes() -> list[RoomBox]:
    return [
        RoomBox(name="로비", zone="로비", floor=1, x=0.0, y=0.0, z=0.0, width=10.0, depth=8.0, height=3.5),
        RoomBox(name="사무실", zone="사무실", floor=2, x=0.0, y=0.0, z=3.5, width=10.0, depth=8.0, height=3.5),
    ]


def test_output_is_binary_3dm_that_rhino3dm_can_read() -> None:
    data = serialize_massing(_boxes())
    # A real .3dm archive begins with the OpenNURBS magic string.
    assert data[:23] == b"3D Geometry File Format"
    model = rhino3dm.File3dm.FromByteArray(data)
    assert model is not None, "serializer output is not a readable binary .3dm"


def test_round_trip_preserves_every_box() -> None:
    boxes = _boxes()
    model = rhino3dm.File3dm.FromByteArray(serialize_massing(boxes))
    assert model is not None
    assert len(model.Objects) == len(boxes)
