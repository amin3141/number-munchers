from __future__ import annotations

import base64
import io
import json
import os
import socket
import subprocess
import time
import uuid
from contextlib import suppress
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Literal
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

import mss
import pyautogui
import pygetwindow as gw
from fastapi import FastAPI, HTTPException
from PIL import Image, ImageDraw
from pydantic import BaseModel, Field, field_validator

pyautogui.FAILSAFE = True
pyautogui.PAUSE = 0.05

AGENT_ROOT = Path(__file__).resolve().parent
GAME_ROOT = AGENT_ROOT.parent
ARTIFACTS_ROOT = AGENT_ROOT / "artifacts"
ARTIFACTS_ROOT.mkdir(exist_ok=True)

app = FastAPI(
    title="Desktop Control Agent",
    version="0.3.0",
    description="FastAPI service for screenshots, desktop automation, and Number Munchers evaluation sessions.",
)


@dataclass
class SessionInfo:
    session_id: str
    process: subprocess.Popen[Any]
    command: list[str]
    cwd: Path
    debug_port: int
    artifact_dir: Path
    stdout_path: Path
    stderr_path: Path
    seed: int | None
    started_at: float
    build_version: str
    last_state_timestamp: int | None = None


SESSIONS: dict[str, SessionInfo] = {}


def _screen_size() -> tuple[int, int]:
    width, height = pyautogui.size()
    return int(width), int(height)


def _clamp_to_screen(x: int, y: int) -> tuple[int, int]:
    width, height = _screen_size()
    return max(0, min(x, width - 1)), max(0, min(y, height - 1))


def _validate_monitor_index(index: int) -> None:
    with mss.mss() as sct:
        if index < 0 or index >= len(sct.monitors):
            raise HTTPException(
                status_code=400,
                detail=f"Monitor index {index} is out of range. Available indexes: 0-{len(sct.monitors) - 1}.",
            )


def _window_to_dict(window: gw.Win32Window) -> dict[str, object]:
    return {
        "title": window.title,
        "left": window.left,
        "top": window.top,
        "width": window.width,
        "height": window.height,
        "is_active": window == gw.getActiveWindow(),
        "is_minimized": window.isMinimized,
        "is_maximized": window.isMaximized,
    }


def _session_to_dict(session: SessionInfo) -> dict[str, object]:
    return {
        "session_id": session.session_id,
        "pid": session.process.pid,
        "command": session.command,
        "cwd": str(session.cwd),
        "debug_port": session.debug_port,
        "artifact_dir": str(session.artifact_dir),
        "stdout_path": str(session.stdout_path),
        "stderr_path": str(session.stderr_path),
        "seed": session.seed,
        "started_at": session.started_at,
        "build_version": session.build_version,
        "last_state_timestamp": session.last_state_timestamp,
        "running": session.process.poll() is None,
        "returncode": session.process.poll(),
    }


def _find_windows(title: str, *, exact: bool) -> list[gw.Win32Window]:
    title_query = title.casefold()
    matches = []
    for window in gw.getAllWindows():
        candidate = window.title.strip()
        if not candidate:
            continue
        normalized = candidate.casefold()
        if (exact and normalized == title_query) or (not exact and title_query in normalized):
            matches.append(window)
    return matches


def _window_is_active(window: gw.Win32Window) -> bool:
    active = gw.getActiveWindow()
    if active is None:
        return False
    return (
        active.title == window.title
        and active.left == window.left
        and active.top == window.top
        and active.width == window.width
        and active.height == window.height
    )


def _find_free_tcp_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        sock.listen(1)
        return int(sock.getsockname()[1])


def _get_tcp_port_owner_pid(port: int) -> int | None:
    result = subprocess.run(
        ["netstat", "-ano", "-p", "tcp"],
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    needle = f"127.0.0.1:{port}"
    for line in result.stdout.splitlines():
        stripped = line.strip()
        if not stripped.startswith("TCP"):
            continue
        parts = stripped.split()
        if len(parts) < 5:
            continue
        local_address, state, pid_text = parts[1], parts[3], parts[4]
        if local_address != needle or state != "LISTENING":
            continue
        with suppress(ValueError):
            return int(pid_text)
    return None


def _is_port_free(port: int) -> bool:
    return _get_tcp_port_owner_pid(port) is None


def _git_build_version() -> str:
    result = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=GAME_ROOT,
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if result.returncode == 0:
        value = result.stdout.strip()
        if value:
            return value
    return "unknown"


def _crop_box_from_request(x: int | None, y: int | None, width: int | None, height: int | None, image: Image.Image) -> tuple[int, int, int, int] | None:
    if x is None and y is None and width is None and height is None:
        return None
    if None in (x, y, width, height):
        raise HTTPException(status_code=400, detail="x, y, width, and height must all be provided for cropping.")
    assert x is not None and y is not None and width is not None and height is not None
    if width <= 0 or height <= 0:
        raise HTTPException(status_code=400, detail="width and height must be positive.")
    left = max(0, x)
    top = max(0, y)
    right = min(image.width, x + width)
    bottom = min(image.height, y + height)
    if left >= right or top >= bottom:
        raise HTTPException(status_code=400, detail="Requested crop does not intersect the captured image.")
    return left, top, right, bottom


def _encode_or_save_image(
    image: Image.Image,
    *,
    image_format: Literal["png", "jpeg"],
    quality: int,
    save_to_disk: bool,
    artifact_dir: Path | None,
    filename_prefix: str,
) -> dict[str, object]:
    save_kwargs: dict[str, object] = {}
    if image_format == "jpeg":
        save_kwargs["quality"] = quality

    response: dict[str, object] = {
        "format": image_format,
        "width": image.width,
        "height": image.height,
    }
    if save_to_disk:
        target_dir = artifact_dir or ARTIFACTS_ROOT
        target_dir.mkdir(parents=True, exist_ok=True)
        extension = "jpg" if image_format == "jpeg" else image_format
        path = target_dir / f"{filename_prefix}-{int(time.time() * 1000)}.{extension}"
        image.save(path, format=image_format.upper(), **save_kwargs)
        response["path"] = str(path)
        return response

    buffer = io.BytesIO()
    image.save(buffer, format=image_format.upper(), **save_kwargs)
    response["image"] = base64.b64encode(buffer.getvalue()).decode("ascii")
    return response


def _capture_monitor_image(monitor_index: int) -> tuple[Image.Image, dict[str, int]]:
    _validate_monitor_index(monitor_index)
    with mss.mss() as sct:
        monitor = dict(sct.monitors[monitor_index])
        img = sct.grab(monitor)
    return Image.frombytes("RGB", img.size, img.rgb), monitor


def _capture_window_image(window: gw.Win32Window) -> tuple[Image.Image, dict[str, int]]:
    bbox = {
        "left": max(0, int(window.left)),
        "top": max(0, int(window.top)),
        "width": max(1, int(window.width)),
        "height": max(1, int(window.height)),
    }
    with mss.mss() as sct:
        img = sct.grab(bbox)
    return Image.frombytes("RGB", img.size, img.rgb), bbox


def _annotate_bounds(image: Image.Image) -> None:
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 0, image.width - 1, image.height - 1), outline=(255, 64, 64), width=2)


def _http_json(url: str, *, method: str = "GET", timeout: float = 5.0) -> dict[str, Any]:
    request = Request(url, method=method)
    try:
        with urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise HTTPException(status_code=502, detail=f"Debug API returned HTTP {exc.code}: {body}") from exc
    except URLError as exc:
        raise HTTPException(status_code=502, detail=f"Unable to reach debug API: {exc.reason}") from exc


def _debug_api_request(port: int, path: str, *, method: str = "GET", params: dict[str, Any] | None = None) -> dict[str, Any]:
    suffix = f"?{urlencode(params)}" if params else ""
    return _http_json(f"http://127.0.0.1:{port}{path}{suffix}", method=method)


def _get_session_or_404(session_id: str) -> SessionInfo:
    session = SESSIONS.get(session_id)
    if session is None:
        raise HTTPException(status_code=404, detail=f"Unknown session: {session_id}")
    return session


def _terminate_process_tree(pid: int) -> None:
    subprocess.run(
        ["taskkill", "/PID", str(pid), "/T", "/F"],
        check=False,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


def _tail_file(path: Path, max_bytes: int = 4000) -> str:
    if not path.exists():
        return ""
    data = path.read_bytes()
    return data[-max_bytes:].decode("utf-8", errors="replace")


class Point(BaseModel):
    x: int = Field(ge=0)
    y: int = Field(ge=0)
    duration: float = Field(default=0.0, ge=0.0, le=5.0)


class ClickRequest(Point):
    button: Literal["left", "middle", "right"] = "left"
    clicks: int = Field(default=1, ge=1, le=5)
    interval: float = Field(default=0.0, ge=0.0, le=1.0)


class TypeRequest(BaseModel):
    text: str = Field(min_length=1, max_length=5000)
    interval: float = Field(default=0.0, ge=0.0, le=1.0)
    press_enter: bool = False


class ScrollRequest(BaseModel):
    clicks: int = Field(description="Positive scrolls up, negative scrolls down.", ge=-5000, le=5000)
    x: int | None = Field(default=None, ge=0)
    y: int | None = Field(default=None, ge=0)


class DragRequest(BaseModel):
    start_x: int = Field(ge=0)
    start_y: int = Field(ge=0)
    end_x: int = Field(ge=0)
    end_y: int = Field(ge=0)
    duration: float = Field(default=0.2, ge=0.0, le=10.0)
    button: Literal["left", "middle", "right"] = "left"


class HotkeyRequest(BaseModel):
    keys: list[str] = Field(min_length=1, max_length=5)

    @field_validator("keys")
    @classmethod
    def validate_keys(cls, value: list[str]) -> list[str]:
        normalized = [key.strip().lower() for key in value if key.strip()]
        if not normalized:
            raise ValueError("At least one non-empty hotkey must be provided.")
        return normalized


class ScreenshotRequest(BaseModel):
    monitor: int = Field(default=1, ge=0)
    format: Literal["png", "jpeg"] = "png"
    quality: int = Field(default=90, ge=1, le=100)
    max_width: int | None = Field(default=None, ge=1, le=10000)
    x: int | None = Field(default=None, ge=0)
    y: int | None = Field(default=None, ge=0)
    width: int | None = Field(default=None, ge=1)
    height: int | None = Field(default=None, ge=1)
    save_to_disk: bool = False
    annotate_bounds: bool = False


class WindowScreenshotRequest(BaseModel):
    title: str = Field(min_length=1, max_length=500)
    exact: bool = False
    format: Literal["png", "jpeg"] = "png"
    quality: int = Field(default=90, ge=1, le=100)
    x: int | None = Field(default=None, ge=0)
    y: int | None = Field(default=None, ge=0)
    width: int | None = Field(default=None, ge=1)
    height: int | None = Field(default=None, ge=1)
    save_to_disk: bool = True
    annotate_bounds: bool = False


class WindowMatchRequest(BaseModel):
    title: str = Field(min_length=1, max_length=500)
    exact: bool = False
    restore_if_minimized: bool = True
    best_effort: bool = True


class StartSessionRequest(BaseModel):
    command: list[str] | None = None
    debug_port: int | None = Field(default=None, ge=1, le=65535)
    seed: int | None = None
    startup_timeout_seconds: float = Field(default=20.0, ge=1.0, le=120.0)
    capture_logs: bool = True
    wait_for: list[Literal["debug_api", "window_visible", "board_initialized", "state_stable"]] = Field(
        default_factory=lambda: ["debug_api"]
    )


class SessionCommandRequest(BaseModel):
    command: Literal["state", "reset", "set-seed", "move", "munch", "pause", "resume", "toggle-pause", "tick-enemies"]
    params: dict[str, str] = Field(default_factory=dict)


@app.get("/health")
def health() -> dict[str, object]:
    width, height = _screen_size()
    return {
        "status": "ok",
        "screen_width": width,
        "screen_height": height,
        "failsafe": pyautogui.FAILSAFE,
        "pause_seconds": pyautogui.PAUSE,
        "game_root": str(GAME_ROOT),
        "session_count": len(SESSIONS),
    }


@app.get("/screen")
def screen_info() -> dict[str, object]:
    width, height = _screen_size()
    with mss.mss() as sct:
        monitors = [
            {
                "index": index,
                "left": monitor["left"],
                "top": monitor["top"],
                "width": monitor["width"],
                "height": monitor["height"],
                "is_virtual": index == 0,
            }
            for index, monitor in enumerate(sct.monitors)
        ]

    x, y = pyautogui.position()
    return {
        "virtual_screen_width": width,
        "virtual_screen_height": height,
        "mouse_x": x,
        "mouse_y": y,
        "monitors": monitors,
    }


@app.post("/screenshot")
def screenshot(request: ScreenshotRequest) -> dict[str, object]:
    image, monitor = _capture_monitor_image(request.monitor)
    crop_box = _crop_box_from_request(request.x, request.y, request.width, request.height, image)
    if crop_box is not None:
        image = image.crop(crop_box)
    if request.max_width and image.width > request.max_width:
        ratio = request.max_width / image.width
        resized_height = max(1, int(image.height * ratio))
        image = image.resize((request.max_width, resized_height))
    if request.annotate_bounds:
        _annotate_bounds(image)
    response = _encode_or_save_image(
        image,
        image_format=request.format,
        quality=request.quality,
        save_to_disk=request.save_to_disk,
        artifact_dir=ARTIFACTS_ROOT,
        filename_prefix=f"monitor-{request.monitor}",
    )
    response["monitor"] = request.monitor
    response["monitor_bounds"] = monitor
    if crop_box is not None:
        response["crop_bounds"] = {
            "x": crop_box[0],
            "y": crop_box[1],
            "width": crop_box[2] - crop_box[0],
            "height": crop_box[3] - crop_box[1],
        }
    return response


@app.get("/mouse")
def mouse_position() -> dict[str, int]:
    x, y = pyautogui.position()
    return {"x": int(x), "y": int(y)}


@app.post("/move")
def move_mouse(request: Point) -> dict[str, object]:
    x, y = _clamp_to_screen(request.x, request.y)
    pyautogui.moveTo(x, y, duration=request.duration)
    return {"status": "moved", "x": x, "y": y}


@app.post("/click")
def click(request: ClickRequest) -> dict[str, object]:
    x, y = _clamp_to_screen(request.x, request.y)
    pyautogui.click(
        x=x,
        y=y,
        clicks=request.clicks,
        interval=request.interval,
        duration=request.duration,
        button=request.button,
    )
    return {
        "status": "clicked",
        "x": x,
        "y": y,
        "button": request.button,
        "clicks": request.clicks,
    }


@app.post("/drag")
def drag_mouse(request: DragRequest) -> dict[str, object]:
    start_x, start_y = _clamp_to_screen(request.start_x, request.start_y)
    end_x, end_y = _clamp_to_screen(request.end_x, request.end_y)
    pyautogui.moveTo(start_x, start_y)
    pyautogui.dragTo(end_x, end_y, duration=request.duration, button=request.button)
    return {
        "status": "dragged",
        "start_x": start_x,
        "start_y": start_y,
        "end_x": end_x,
        "end_y": end_y,
        "button": request.button,
    }


@app.post("/scroll")
def scroll(request: ScrollRequest) -> dict[str, object]:
    if request.x is not None and request.y is not None:
        x, y = _clamp_to_screen(request.x, request.y)
        pyautogui.moveTo(x, y)
    else:
        x = y = None

    pyautogui.scroll(request.clicks)
    return {"status": "scrolled", "clicks": request.clicks, "x": x, "y": y}


@app.post("/type")
def type_text(request: TypeRequest) -> dict[str, object]:
    pyautogui.write(request.text, interval=request.interval)
    if request.press_enter:
        pyautogui.press("enter")
    return {
        "status": "typed",
        "length": len(request.text),
        "press_enter": request.press_enter,
    }


@app.post("/hotkey")
def press_hotkey(request: HotkeyRequest) -> dict[str, object]:
    pyautogui.hotkey(*request.keys)
    return {"status": "pressed", "keys": request.keys}


@app.get("/windows")
def list_windows() -> dict[str, object]:
    windows = [_window_to_dict(window) for window in gw.getAllWindows() if window.title.strip()]
    return {"count": len(windows), "windows": windows}


@app.post("/windows/activate")
def activate_window(request: WindowMatchRequest) -> dict[str, object]:
    matches = _find_windows(request.title, exact=request.exact)
    if not matches:
        raise HTTPException(status_code=404, detail=f'No window found matching "{request.title}".')

    window = matches[0]
    was_minimized = bool(window.isMinimized)
    error: str | None = None

    if request.restore_if_minimized and window.isMinimized:
        with suppress(Exception):
            window.restore()
        time.sleep(0.1)

    try:
        window.activate()
    except Exception as exc:
        error = str(exc)

    is_active_after = _window_is_active(window)
    if not is_active_after:
        with suppress(Exception):
            window.minimize()
        time.sleep(0.1)
        with suppress(Exception):
            window.restore()
        time.sleep(0.15)
        try:
            window.activate()
        except Exception as exc:
            error = str(exc)
        is_active_after = _window_is_active(window)

    response = {
        "requested_title": request.title,
        "matched_count": len(matches),
        "matched_window_title": window.title,
        "was_minimized": was_minimized,
        "activation_attempted": True,
        "is_active_after": is_active_after,
        "error": error,
        "window": _window_to_dict(window),
    }
    if not is_active_after and not request.best_effort:
        raise HTTPException(status_code=500, detail=response)
    response["status"] = "activated" if is_active_after else "best_effort_failed"
    return response


@app.post("/windows/screenshot")
def screenshot_window(request: WindowScreenshotRequest) -> dict[str, object]:
    matches = _find_windows(request.title, exact=request.exact)
    if not matches:
        raise HTTPException(status_code=404, detail=f'No window found matching "{request.title}".')

    window = matches[0]
    image, bounds = _capture_window_image(window)
    crop_box = _crop_box_from_request(request.x, request.y, request.width, request.height, image)
    if crop_box is not None:
        image = image.crop(crop_box)
    if request.annotate_bounds:
        _annotate_bounds(image)
    response = _encode_or_save_image(
        image,
        image_format=request.format,
        quality=request.quality,
        save_to_disk=request.save_to_disk,
        artifact_dir=ARTIFACTS_ROOT,
        filename_prefix="window",
    )
    response["matched_count"] = len(matches)
    response["matched_window_title"] = window.title
    response["window_bounds"] = bounds
    if crop_box is not None:
        response["crop_bounds"] = {
            "x": crop_box[0],
            "y": crop_box[1],
            "width": crop_box[2] - crop_box[0],
            "height": crop_box[3] - crop_box[1],
        }
    return response


@app.get("/sessions")
def list_sessions() -> dict[str, object]:
    return {"count": len(SESSIONS), "sessions": [_session_to_dict(session) for session in SESSIONS.values()]}


def _session_debug_health(session: SessionInfo) -> dict[str, object]:
    port_owner_pid = _get_tcp_port_owner_pid(session.debug_port)
    running = session.process.poll() is None
    debug_health: dict[str, Any] | None = None
    debug_server_reachable = False
    health_matches_session = False
    state: dict[str, Any] | None = None
    try:
        debug_health = _debug_api_request(session.debug_port, "/health")
        debug_server_reachable = True
        health_matches_session = (
            debug_health.get("session_id") == session.session_id
            and debug_health.get("process_id") == session.process.pid
            and debug_health.get("debug_port") == session.debug_port
        )
    except HTTPException:
        debug_health = None

    try:
        state = _debug_api_request(session.debug_port, "/state")
        timestamp = state.get("state_timestamp")
        if isinstance(timestamp, int):
            session.last_state_timestamp = timestamp
    except HTTPException:
        state = None

    return {
        "session_id": session.session_id,
        "pid": session.process.pid,
        "running": running,
        "debug_port": session.debug_port,
        "port_owner_pid": port_owner_pid,
        "debug_server_reachable": debug_server_reachable,
        "health_matches_session": health_matches_session,
        "last_state_timestamp": session.last_state_timestamp,
        "debug_health": debug_health,
        "state_summary": None if state is None else {
            "score": state.get("score"),
            "lives": state.get("lives"),
            "round": state.get("round"),
            "paused": state.get("paused"),
            "game_over": state.get("game_over"),
            "window_focused": state.get("window_focused"),
            "accepting_input": state.get("accepting_input"),
        },
    }


def _window_visible(title: str) -> bool:
    matches = _find_windows(title, exact=False)
    return any(window.width > 0 and window.height > 0 and not window.isMinimized for window in matches)


def _state_meets_condition(state: dict[str, Any], condition: str) -> bool:
    if condition == "board_initialized":
        board = state.get("board")
        player = state.get("player")
        layout = state.get("layout")
        return isinstance(board, list) and bool(board) and isinstance(player, dict) and isinstance(layout, dict)
    if condition == "state_stable":
        return bool(state.get("state_stable"))
    raise ValueError(f"Unsupported wait_for condition: {condition}")


@app.post("/sessions/start")
def start_session(request: StartSessionRequest) -> dict[str, object]:
    session_id = uuid.uuid4().hex[:12]
    artifact_dir = ARTIFACTS_ROOT / session_id
    artifact_dir.mkdir(parents=True, exist_ok=False)
    stdout_path = artifact_dir / "stdout.log"
    stderr_path = artifact_dir / "stderr.log"
    debug_port = request.debug_port or _find_free_tcp_port()
    build_version = _git_build_version()

    command = request.command or [
        "cmd.exe",
        "/c",
        "gradlew.bat",
        "run",
        f"-PnumberMunchers.debug.port={debug_port}",
    ]
    if request.seed is not None and request.command is None:
        command.append(f"-PnumberMunchers.seed={request.seed}")

    stdout_handle = stdout_path.open("w", encoding="utf-8") if request.capture_logs else subprocess.DEVNULL
    stderr_handle = stderr_path.open("w", encoding="utf-8") if request.capture_logs else subprocess.DEVNULL

    env = os.environ.copy()
    env["NUMBER_MUNCHERS_SESSION_ID"] = session_id
    env["NUMBER_MUNCHERS_STARTED_AT"] = str(int(time.time() * 1000))
    env["NUMBER_MUNCHERS_BUILD_VERSION"] = build_version

    process = subprocess.Popen(
        command,
        cwd=GAME_ROOT,
        stdout=stdout_handle,
        stderr=stderr_handle,
        stdin=subprocess.DEVNULL,
        env=env,
        creationflags=getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0),
    )

    session = SessionInfo(
        session_id=session_id,
        process=process,
        command=command,
        cwd=GAME_ROOT,
        debug_port=debug_port,
        artifact_dir=artifact_dir,
        stdout_path=stdout_path,
        stderr_path=stderr_path,
        seed=request.seed,
        started_at=time.time(),
        build_version=build_version,
    )
    SESSIONS[session_id] = session

    deadline = time.time() + request.startup_timeout_seconds
    debug_state: dict[str, Any] | None = None
    stable_samples = 0
    while time.time() < deadline:
        if process.poll() is not None:
            raise HTTPException(
                status_code=500,
                detail={
                    "message": "Game process exited before debug API became ready.",
                    "stdout_tail": _tail_file(stdout_path),
                    "stderr_tail": _tail_file(stderr_path),
                    "returncode": process.returncode,
                },
            )
        try:
            if "debug_api" in request.wait_for:
                debug_health = _debug_api_request(debug_port, "/health")
                if (
                    debug_health.get("session_id") != session_id
                    or debug_health.get("process_id") != process.pid
                    or debug_health.get("debug_port") != debug_port
                ):
                    raise HTTPException(status_code=502, detail="Debug API responded for a different session.")
            if "window_visible" in request.wait_for and not _window_visible("Number Munchers Deluxe"):
                time.sleep(0.25)
                continue
            debug_state = _debug_api_request(debug_port, "/state")
            timestamp = debug_state.get("state_timestamp")
            if isinstance(timestamp, int):
                session.last_state_timestamp = timestamp
            if "board_initialized" in request.wait_for and not _state_meets_condition(debug_state, "board_initialized"):
                time.sleep(0.25)
                continue
            if "state_stable" in request.wait_for:
                if debug_state.get("session_id") == session_id and debug_state.get("process_id") == process.pid:
                    stable_samples += 1
                else:
                    stable_samples = 0
                debug_state["state_stable"] = stable_samples >= 2
                if stable_samples < 2:
                    time.sleep(0.25)
                    continue
            break
        except HTTPException:
            time.sleep(0.5)

    if debug_state is None:
        raise HTTPException(
            status_code=504,
            detail={
                "message": "Timed out waiting for game debug API.",
                "stdout_tail": _tail_file(stdout_path),
                "stderr_tail": _tail_file(stderr_path),
            },
        )

    return {
        "status": "started",
        "session": _session_to_dict(session),
        "debug_port": debug_port,
        "debug_health": _debug_api_request(debug_port, "/health"),
        "state": debug_state,
    }


@app.get("/sessions/{session_id}")
def get_session(session_id: str) -> dict[str, object]:
    session = _get_session_or_404(session_id)
    return {
        "session": _session_to_dict(session),
        "health": _session_debug_health(session),
        "stdout_tail": _tail_file(session.stdout_path),
        "stderr_tail": _tail_file(session.stderr_path),
    }


@app.post("/sessions/{session_id}/stop")
def stop_session(session_id: str) -> dict[str, object]:
    session = _get_session_or_404(session_id)
    if session.process.poll() is None:
        _terminate_process_tree(session.process.pid)
        with suppress(Exception):
            session.process.wait(timeout=5)
    port_released = False
    release_deadline = time.time() + 5.0
    while time.time() < release_deadline:
        if _is_port_free(session.debug_port):
            port_released = True
            break
        time.sleep(0.2)
    return {"status": "stopped", "session": _session_to_dict(session), "port_released": port_released}


@app.get("/sessions/{session_id}/logs")
def get_session_logs(session_id: str) -> dict[str, object]:
    session = _get_session_or_404(session_id)
    return {
        "stdout_tail": _tail_file(session.stdout_path),
        "stderr_tail": _tail_file(session.stderr_path),
    }


@app.get("/sessions/{session_id}/state")
def get_session_state(session_id: str) -> dict[str, object]:
    session = _get_session_or_404(session_id)
    state = _debug_api_request(session.debug_port, "/state")
    timestamp = state.get("state_timestamp")
    if isinstance(timestamp, int):
        session.last_state_timestamp = timestamp
    return state


@app.get("/sessions/{session_id}/health")
def get_session_health(session_id: str) -> dict[str, object]:
    session = _get_session_or_404(session_id)
    return _session_debug_health(session)


@app.post("/sessions/{session_id}/command")
def run_session_command(session_id: str, request: SessionCommandRequest) -> dict[str, object]:
    session = _get_session_or_404(session_id)
    return _debug_api_request(session.debug_port, f"/command/{request.command}", method="POST", params=request.params)
