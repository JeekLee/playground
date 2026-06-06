/**
 * Minimal GLB(glTF-Binary 2.0) container reader — pulls
 * `scenes[0].extras.programJson` out of a .glb without three.js.
 *
 * The architecture BC embeds the massing program (hotspot anchors + room
 * table) into the preview .glb at generation time (glb-extras spec D2), so
 * a history card can restore what the streaming SSE payload carried.
 * Container layout: 12-byte header (magic "glTF", version, length) followed
 * by length-prefixed chunks; chunk 0 is always JSON.
 *
 * Every failure path resolves to `null` — legacy .glb without extras, HTTP
 * errors, malformed bytes. Callers degrade exactly like a missing
 * programJson today.
 */
export async function fetchGlbProgramJson(
  url: string,
): Promise<Record<string, unknown> | null> {
  try {
    const res = await fetch(url); // same-origin — gateway session cookie rides along
    if (!res.ok) return null;
    const buf = await res.arrayBuffer();
    if (buf.byteLength < 20) return null;
    const view = new DataView(buf);
    if (view.getUint32(0, true) !== 0x46546c67) return null; // "glTF"
    if (view.getUint32(4, true) !== 2) return null; // version 2 only
    const jsonLength = view.getUint32(12, true);
    if (view.getUint32(16, true) !== 0x4e4f534a) return null; // chunk0 type "JSON"
    if (20 + jsonLength > buf.byteLength) return null;
    const jsonText = new TextDecoder().decode(new Uint8Array(buf, 20, jsonLength));
    const doc = JSON.parse(jsonText) as {
      scenes?: { extras?: { programJson?: unknown } }[];
    };
    const pj = doc.scenes?.[0]?.extras?.programJson;
    return typeof pj === 'object' && pj !== null
      ? (pj as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
}
