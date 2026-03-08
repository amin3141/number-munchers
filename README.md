# Number Munchers Deluxe

JavaFX recreation of Number Munchers with a paired desktop-control agent for automated evaluation.

## Project Layout

- `src/main/java/com/pairsys/numbermunchers`: JavaFX game source
- `codex-desktop-agent`: FastAPI desktop and evaluation agent
- `run-number-munchers.bat`: convenience launcher for the game
- `build.gradle`: Gradle build and runtime wiring

## Requirements

- Java 21
- Gradle wrapper support via `gradlew.bat` or a local Gradle installation
- Windows desktop session for live game automation
- Python 3.12 for `codex-desktop-agent`

## Running The Game

Wrapper:

```powershell
.\gradlew.bat run
```

Batch file:

```powershell
.\run-number-munchers.bat
```

Global Gradle:

```powershell
gradle run
```

## Controls

- Move: Arrow keys or `WASD`
- Munch current tile: `Space`
- Pause/unpause: `P`
- Restart after game over: `Enter`

## Debug And Evaluation API

The game now starts a local debug server by default on `http://127.0.0.1:8765`.

This API is meant for the desktop agent or other local tooling. It exposes real game state and a small command surface so automated evaluators do not need to infer everything from screenshots.

### Debug Endpoints

- `GET /health`
- `GET /state`
- `POST /command/reset`
- `POST /command/set-seed?seed=123`
- `POST /command/move?direction=left|right|up|down`
- `POST /command/munch`
- `POST /command/pause`
- `POST /command/resume`
- `POST /command/toggle-pause`
- `POST /command/tick-enemies?count=1`

### State Payload

`GET /state` returns structured JSON including:

- session identity:
  - `session_id`
  - `process_id`
  - `started_at`
  - `build_version`
  - `seed`
  - `debug_port`
- timing:
  - `state_timestamp`
  - `round_started_at`
  - `enemy_movement_enabled_at`
  - `milliseconds_since_round_start`
  - `milliseconds_until_enemy_movement`
- readiness:
  - `accepting_input`
  - `window_focused`
  - `round_intro_active`
  - `enemy_ai_active`
- score, lives, round, edible targets remaining
- current rule text
- paused/game-over flags
- player row/column
- enemy positions
- full board contents with `value`, `edible`, and `munched` for every cell
- layout data:
  - `board`
  - `outer_border`
  - `hud`
  - `cell_inset`
  - `cell_size`
  - corner `cell_bounds`

`GET /health` now also includes the same session identity fields plus `last_state_timestamp`.

### Configuring The Debug Server

Override the default debug port:

```powershell
.\gradlew.bat run -PnumberMunchers.debug.port=9001
```

Run with a deterministic seed:

```powershell
.\gradlew.bat run -PnumberMunchers.seed=12345
```

Disable the debug server:

```powershell
.\gradlew.bat run -PnumberMunchers.debug.port=0
```

The batch launcher forwards extra arguments too:

```powershell
.\run-number-munchers.bat -PnumberMunchers.seed=42 -PnumberMunchers.debug.port=8766
```

## Desktop Agent

The paired desktop agent lives in [`codex-desktop-agent`](./codex-desktop-agent). It now supports:

- screenshots and mouse/keyboard automation
- window listing and activation
- window-scoped screenshots with crop and saved artifact paths
- launching Number Munchers sessions
- auto-assigning a unique debug port per session
- waiting for richer readiness conditions such as window visibility and board initialization
- querying game state through the debug bridge
- sending structured game commands such as `move`, `munch`, and `reset`
- verifying session PID plus port ownership through `/sessions/{id}/health`
- capturing stdout/stderr logs under `codex-desktop-agent/artifacts`

Start the agent:

```powershell
cd .\codex-desktop-agent
uv run python main.py
```

Open API docs:

- `http://127.0.0.1:8000/docs`

### Example Agent Workflow

Start a deterministic session:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri http://127.0.0.1:8000/sessions/start `
  -ContentType "application/json" `
  -Body '{"seed":42,"wait_for":["debug_api","window_visible","board_initialized","state_stable"]}'
```

Read the current game state:

```powershell
Invoke-RestMethod http://127.0.0.1:8000/sessions/<session_id>/state
```

Read structured session health:

```powershell
Invoke-RestMethod http://127.0.0.1:8000/sessions/<session_id>/health
```

Move the player:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri http://127.0.0.1:8000/sessions/<session_id>/command `
  -ContentType "application/json" `
  -Body '{"command":"move","params":{"direction":"right"}}'
```

Munch the current tile:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri http://127.0.0.1:8000/sessions/<session_id>/command `
  -ContentType "application/json" `
  -Body '{"command":"munch","params":{}}'
```

Stop the session:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri http://127.0.0.1:8000/sessions/<session_id>/stop
```

Capture a saved screenshot of the game window:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri http://127.0.0.1:8000/windows/screenshot `
  -ContentType "application/json" `
  -Body '{"title":"Number Munchers Deluxe","save_to_disk":true}'
```

## Building

Create the distributable artifacts:

```powershell
.\gradlew.bat build
```

The build outputs go under `build/`.

## Notes For AI Evaluation

- Prefer the debug API for assertions and state reads; use screenshots only for visual validation.
- Use a fixed seed when comparing behavioral changes.
- Pause the game before stepping enemies manually if you want reproducible inspection.
- The desktop agent is still useful for launch, focus, screenshots, and fallback UI-driving when needed.
