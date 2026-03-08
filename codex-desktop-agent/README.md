# Desktop Control Agent

Windows desktop automation and Number Munchers session control.

## Recommended Mode

Use the HTTP service:

```powershell
uv run python main.py
```

Or:

```powershell
uv run desktop-agent
```

The API listens on `http://127.0.0.1:8000`.

The stdio MCP server still exists in `mcp_server.py`, but this repo currently prefers the HTTP workflow.

## Core Capabilities

- desktop screenshots, mouse, keyboard, hotkeys
- window enumeration and best-effort activation
- window-scoped screenshots with optional crop, bounds annotation, and saved artifact paths
- Number Munchers process launch and stop
- auto-assigned per-session debug ports
- session health that verifies PID, port ownership, and debug API identity
- structured game state and commands proxied from the JavaFX debug server

## Key HTTP Endpoints

- `GET /health`
- `GET /screen`
- `POST /screenshot`
- `GET /mouse`
- `POST /move`
- `POST /click`
- `POST /drag`
- `POST /scroll`
- `POST /type`
- `POST /hotkey`
- `GET /windows`
- `POST /windows/activate`
- `POST /windows/screenshot`
- `GET /sessions`
- `POST /sessions/start`
- `GET /sessions/{session_id}`
- `GET /sessions/{session_id}/health`
- `POST /sessions/{session_id}/stop`
- `GET /sessions/{session_id}/logs`
- `GET /sessions/{session_id}/state`
- `POST /sessions/{session_id}/command`

## Session Behavior

`POST /sessions/start` now:

- chooses a free debug port automatically unless you supply one
- returns the chosen `debug_port`
- passes `session_id`, `started_at`, and `build_version` into the JavaFX process
- can wait for richer readiness conditions with:
  - `debug_api`
  - `window_visible`
  - `board_initialized`
  - `state_stable`

Example:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri http://127.0.0.1:8000/sessions/start `
  -ContentType "application/json" `
  -Body '{"seed":42,"wait_for":["debug_api","window_visible","board_initialized","state_stable"]}'
```

`GET /sessions/{id}/health` returns:

- `pid`
- `running`
- `debug_port`
- `port_owner_pid`
- `debug_server_reachable`
- `health_matches_session`
- `last_state_timestamp`

## Screenshot Examples

Save a cropped monitor capture:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri http://127.0.0.1:8000/screenshot `
  -ContentType "application/json" `
  -Body '{"monitor":1,"x":100,"y":100,"width":640,"height":480,"save_to_disk":true}'
```

Capture just the game window:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri http://127.0.0.1:8000/windows/screenshot `
  -ContentType "application/json" `
  -Body '{"title":"Number Munchers Deluxe","save_to_disk":true,"annotate_bounds":true}'
```

## Notes

- The default game launcher assumes the game project is the parent directory of this folder.
- Session artifacts and saved screenshots go under `codex-desktop-agent/artifacts/`.
- `pyautogui.FAILSAFE = True` is enabled.
