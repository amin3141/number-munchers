# Agent Instructions

## Workspace Overview

- The JavaFX game lives in `src/main/java/com/pairsys/numbermunchers/`.
- The desktop automation and evaluation service lives in `codex-desktop-agent/`.
- The game root for this workspace is `F:\data\workspaces\pairsys\number-munchers`.

## Preferred Evaluation Workflow

- Prefer the game's local debug API on `http://127.0.0.1:8765` for structured state inspection and commands.
- Use the desktop agent for launching the game, checking window state, screenshots, focus management, and fallback desktop automation.
- Prefer deterministic runs by setting a seed when launching evaluation sessions.

## Desktop Agent Requirements

Before trying to use the desktop agent, check whether it is already running:

```powershell
Invoke-RestMethod http://127.0.0.1:8000/health
```

If that request fails, start the agent yourself from the workspace copy in `codex-desktop-agent/` and then continue:

```powershell
cd F:\data\workspaces\pairsys\number-munchers\codex-desktop-agent
uv run python main.py
```

After starting it, use:

- API docs: `http://127.0.0.1:8000/docs`
- Health check: `http://127.0.0.1:8000/health`

## What The Desktop Agent Can Do

- start and stop Number Munchers sessions
- wait for the JavaFX debug API to become ready
- read structured game state
- send game commands such as `move`, `munch`, `pause`, `resume`, and `reset`
- capture session stdout and stderr logs
- perform screenshots, mouse actions, keyboard input, and window activation when needed

## Session Workflow

Typical session flow:

1. Ensure the desktop agent is running.
2. Start a game session via `POST /sessions/start`.
3. Read state via `GET /sessions/{session_id}/state`.
4. Drive the game via `POST /sessions/{session_id}/command`.
5. Stop the session via `POST /sessions/{session_id}/stop`.

## Game Debug API

The JavaFX game exposes a local debug server by default on `http://127.0.0.1:8765`.

Useful endpoints:

- `GET /health`
- `GET /state`
- `POST /command/reset`
- `POST /command/set-seed?seed=123`
- `POST /command/move?direction=left|right|up|down`
- `POST /command/munch`
- `POST /command/pause`
- `POST /command/resume`
- `POST /command/tick-enemies?count=1`

## Working Rules

- When evaluating gameplay behavior, prefer debug-state assertions over screenshot-only inference.
- When comparing behavior across changes, use a fixed seed.
- If the desktop agent is not running, start it yourself instead of asking the user to do it.
- Prefer the HTTP desktop agent workflow in this repo. Do not rely on MCP for `desktop` here.
- Keep changes compatible with Windows and the existing Gradle and `uv` workflows in this repo.
