# Talk to Figma MCP — Setup

> Why: the official `claude_ai_Figma` MCP is rate-limited on the Starter plan and blocked all our screenshot + metadata calls during the M1 design regen. `cursor-talk-to-figma-mcp` (by sonnylazuardi) bypasses Figma Cloud API entirely — it bridges Claude ⇄ a local WebSocket server ⇄ a Figma plugin running inside an actual Figma session. No Cloud-API quota involvement.

## Architecture

Everything runs on the same host (Spark). Claude Code, the MCP server, the WebSocket bridge, and the Figma session (via the host's Firefox) all share `localhost`. No SSH port forwarding required.

```
Spark (SSH host)
├── Claude Code
│     │ stdio
│     ▼
│   bunx cursor-talk-to-figma-mcp@latest    ← MCP server (auto-launched per .mcp.json)
│     │
│     │ WebSocket → localhost:3055
│     ▼
│   bun socket                              ← WebSocket bridge (manual, persistent)
│     ▲
│     │ WebSocket → localhost:3055
│     │
└── Firefox  →  figma.com  →  Cursor MCP Plugin (loaded inside the Figma web session)
```

The MCP server sends commands to the bridge; the plugin (running in the Spark-side Firefox tab) picks them up and executes them inside the Figma web session. No Figma Cloud API in the path.

> Earlier flow ran Figma on the user's *local* machine and used `ssh -NL 3055:localhost:3055` to reach the bridge on Spark. That still works, but the Spark-local flow below is the current default — it removes the per-session port-forward step.

## One-time setup on Spark

1. **Install bun** (the JS runtime the socket + MCP server both use):
   ```bash
   curl -fsSL https://bun.sh/install | bash
   exec $SHELL          # reload PATH
   bun --version        # confirm
   ```

2. **Clone the repo** (needed for `bun socket`; the MCP server itself runs via `bunx` and does not need the checkout):
   ```bash
   git clone https://github.com/sonnylazuardi/cursor-talk-to-figma-mcp.git ~/tools/talk-to-figma
   cd ~/tools/talk-to-figma
   bun install
   ```

3. **Run the WebSocket bridge.** Default port is 3055, hostname defaults to localhost. Since Figma also lives on Spark, localhost is fine — no need to flip `0.0.0.0` in `src/socket.ts`.
   ```bash
   cd ~/tools/talk-to-figma
   bun socket           # logs "WebSocket server running on port 3055"
   ```
   For background use, run inside `tmux`/`screen` or:
   ```bash
   nohup bun socket > /tmp/talk-to-figma-socket.log 2>&1 &
   ```

4. **Make sure a desktop session + Firefox are available on Spark.** Any GUI access path works:
   - X11 forwarding from a local SSH client (`ssh -X spark` then `firefox &`),
   - VNC / xrdp into a Spark desktop session,
   - a noVNC / xpra browser bridge.

   The Firefox process runs on Spark; only the pixels leave. The plugin's WebSocket connection stays on Spark `localhost`.

5. **Install the Cursor MCP Plugin** in the Spark-side Firefox Figma session (one time per Figma account):
   https://www.figma.com/community/plugin/1485687494525374295/cursor-talk-to-figma-mcp-plugin

## Per-session setup

No port forwarding needed.

1. **Confirm `bun socket` is up on Spark** (`ss -tlnp | grep 3055`). If not, start it (`nohup bun socket …`).

2. **In Spark Firefox**, open Figma → the file you want Claude to edit → `Plugins → Cursor MCP Plugin`.

3. In the plugin panel:
   - WebSocket URL: `ws://localhost:3055` (default; this is Spark's loopback).
   - Click `Connect` → should turn green / "Connected to WebSocket server."
   - Pick a channel name (any string, e.g. a generated 8-char id) → click `Join`.

4. **Tell Claude the channel name** when you ask it to do design work — Claude's first MCP call must be `join_channel` with the same name. (The product-designer agent prompt should be parameterized with the channel.)

## Restart Claude Code

After `.mcp.json` is committed and you've installed bun + plugin, restart your Claude Code session so the new MCP server is loaded:

```bash
# in the running Claude Code session
/exit
# then re-launch claude code from the same project directory
```

The new tools (prefix `mcp__TalkToFigma__*`) will be available to subagents after restart.

## Tool surface (for product-designer)

The MCP server exposes ~30 tools. The product-designer agent's `tools:` allowlist (in `.claude/agents/product-designer.md`) is set to the subset actually useful for Stage 2 design generation:

- **Connection:** `join_channel`
- **Document & selection:** `get_document_info`, `get_selection`, `read_my_design`, `get_node_info`, `get_nodes_info`, `set_focus`, `set_selections`
- **Creation:** `create_frame`, `create_rectangle`, `create_text`, `create_component_instance`, `clone_node`
- **Layout:** `set_layout_mode`, `set_padding`, `set_axis_align`, `set_layout_sizing`, `set_item_spacing`
- **Styling:** `set_fill_color`, `set_stroke_color`, `set_corner_radius`, `set_text_content`, `set_multiple_text_contents`
- **Editing:** `move_node`, `resize_node`, `delete_node`, `delete_multiple_nodes`
- **Components/styles:** `get_styles`, `get_local_components`
- **Export:** `export_node_as_image` ← the one that was rate-limited on the official MCP. Outputs PNG/JPG/SVG/PDF (currently returns base64 text; agent saves to disk).

## Differences from the official `claude_ai_Figma` MCP

| Capability | Official MCP | Talk to Figma |
|---|---|---|
| Quota | Starter-plan cap, blocked our run | None — runs in user's Figma session |
| Create a new Figma *file* | ✅ `create_new_file` | ❌ user creates the file manually, then plugin joins it |
| Generate full screen via natural-language → JSX bridge (`use_figma`) | ✅ | ❌ agent must call primitive shape/text/layout tools step by step |
| Export node → image | ✅ `get_screenshot` (rate-limited) | ✅ `export_node_as_image` |
| Read existing public files | ✅ `get_design_context` from URL | ❌ only the file the plugin is attached to |
| Iteration speed | High (one bulk call) | Medium (many primitive calls) — but no rate limit |
| Visibility | Headless — agent reports URL after | User watches it happen live in Figma — great for collaboration |

**Practical implication for our M1 work:** Talk to Figma needs more turns to build the same 4 frames (no single `use_figma` "generate this layout" call), but those turns are unrestricted. Net throughput is higher whenever the official MCP would hit quota mid-run.

## Troubleshooting

- **Plugin shows "disconnected" after connecting:** `bun socket` process died on Spark. `ss -tlnp | grep 3055` to confirm, then restart it (`nohup bun socket > /tmp/talk-to-figma-socket.log 2>&1 &`).
- **Claude reports `mcp__TalkToFigma__*` tool not found:** Claude Code session predates `.mcp.json` change. Restart the session.
- **`bun: command not found` when re-dispatching:** the `bunx` invocation in `.mcp.json` runs on the SSH host where Claude Code is. Make sure bun is on PATH for the user/shell Claude Code launched under (`echo $PATH | grep bun`). If not, edit `.mcp.json` to use the absolute path: `"command": "/home/<user>/.bun/bin/bunx"`.
- **All commands hang:** the plugin hasn't joined a channel yet, or it's joined a different channel than Claude is calling. Confirm the channel name matches the one passed to `join_channel`.
- **Plugin can't reach `ws://localhost:3055`:** Firefox is running somewhere other than Spark (e.g., your local laptop). Either move Firefox onto Spark, or fall back to the legacy `ssh -NL 3055:localhost:3055 spark` flow.

## Legacy flow — Figma on a separate local machine

If for some reason Figma must run on a different machine than `bun socket` (e.g., Spark has no GUI access, or the user prefers their local Figma desktop), the original flow still works:

1. Spark: run `bun socket` as above.
2. Local terminal: `ssh -NL 3055:localhost:3055 <spark-host-alias>`. Keep the terminal open for the session.
3. Local Figma desktop/web: install the plugin once, open the target file, `Plugins → Cursor MCP Plugin`, `Connect` to `ws://localhost:3055` (the forwarded port), pick a channel, `Join`.

Everything else (channel name handoff to Claude, restart instructions, tool surface) is identical.
