# CLAUDE.md — playground project conventions for AI agents

This file is auto-loaded into every Claude / Claude Code session that
opens this repo. Keep it short and operational; deep rationale lives in
`docs/adr/` and `docs/design/`.

## Frontend visual changes — pre-flight

Before touching anything that affects the rendered UI (component
markup, layout, spacing, color, copy, animation, accessibility), run
this pre-flight in order:

1. **Identify the milestone** the change touches (typically M1 / M2 /
   etc.). Read the matching `docs/design/<Mx>-*.md` first. These
   documents are the 1st-class design source — components, layout,
   spacing tokens, copy, and per-state behavior are written out in
   prose. Most of the time this is enough.

2. **If the prose underspecifies** (exact proportions, fine spacing,
   color tokens, fonts), pull the frame from Figma. Default tool is
   `mcp__TalkToFigma__*` — it talks to the user's local Figma desktop
   plugin, so it costs no Anthropic quota. The user has to open Figma
   and join a channel; ask for that channel id explicitly before any
   `TalkToFigma` call.

3. **`mcp__claude_ai_Figma__*` is the last resort** because it
   consumes the Anthropic Figma integration quota. Use it only when
   (1) and (2) cannot answer the question (e.g., user is not at their
   machine, or the data is only reachable via the cloud integration).

4. **If the design source — design doc and Figma — has nothing about
   the change**, stop and ask the user before writing code:

   > "이건 Figma / design doc 어디에도 없는 즉흥 변경입니다. design
   > context 문서에 추가할까요? 아니면 그대로 진행할까요?"

5. **After landing the change**, reflect it in the matching
   `docs/design/<Mx>-*.md` (a one-line note in the relevant section is
   fine), or open a follow-up issue if the doc change is non-trivial.
   The design doc must not silently fall out of sync with the
   shipped UI.

This applies whether the request is handled by the main agent or
delegated to `frontend-implementer`. The `frontend-design` skill that
`frontend-implementer` mandates is a stronger version of the same
discipline — the rule above is the floor everyone must meet.

## Branches and worktrees

All implementation work — even single-file fixes once the project is
past M0 — happens inside a git worktree under `.claude/worktrees/`,
not the main checkout:

1. `EnterWorktree({ name: "<topic>" })` before any code edit.
2. Edit, build, verify inside the worktree.
3. Commit, push, open a PR with `gh pr create`.
4. Merge with `gh pr merge --rebase --delete-branch`.
5. `ExitWorktree({ action: "remove", discard_changes: true })` to
   tear the worktree down (its commit is already on `main`).
6. `git fetch origin && git reset --hard origin/main` to sync the
   main checkout.

Don't push from the main checkout directly. Don't open PRs that span
multiple worktrees.
