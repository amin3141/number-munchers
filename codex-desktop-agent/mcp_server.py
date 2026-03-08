from __future__ import annotations

import json
import os
import sys
import traceback
from dataclasses import dataclass
from typing import Any, Callable

from fastapi import HTTPException
from pydantic import ValidationError

import desktop_agent

JSON = dict[str, Any]
ToolHandler = Callable[[dict[str, Any]], Any]
PROTOCOL_VERSIONS = ("2025-03-26", "2024-11-05")
DEBUG_LOG_PATH = os.environ.get(
    "DESKTOP_AGENT_MCP_LOG",
    str(desktop_agent.ARTIFACTS_ROOT / "mcp-server-debug.log"),
)


def _debug_log(message: str) -> None:
    try:
        with open(DEBUG_LOG_PATH, "a", encoding="utf-8") as handle:
            handle.write(message + "\n")
    except OSError:
        pass


@dataclass(frozen=True)
class ToolDefinition:
    name: str
    description: str
    input_schema: JSON
    handler: ToolHandler


def _empty_schema() -> JSON:
    return {"type": "object", "properties": {}, "additionalProperties": False}


def _json_result(value: Any, *, is_error: bool = False) -> JSON:
    text = json.dumps(value, ensure_ascii=True)
    result: JSON = {
        "content": [{"type": "text", "text": text}],
        "isError": is_error,
    }
    if not is_error and isinstance(value, dict):
        result["structuredContent"] = value
    return result


def _model_tool(model: type, func: Callable[[Any], Any]) -> ToolHandler:
    def handler(arguments: dict[str, Any]) -> Any:
        payload = model(**arguments)
        return func(payload)

    return handler


def _plain_tool(func: Callable[[], Any]) -> ToolHandler:
    def handler(arguments: dict[str, Any]) -> Any:
        if arguments:
            raise ValueError("This tool does not accept arguments.")
        return func()

    return handler


def _session_lookup(arguments: dict[str, Any], field: str = "session_id") -> str:
    session_id = arguments.get(field)
    if not isinstance(session_id, str) or not session_id:
        raise ValueError(f'"{field}" is required and must be a non-empty string.')
    return session_id


def _get_session_handler(arguments: dict[str, Any]) -> Any:
    return desktop_agent.get_session(_session_lookup(arguments))


def _stop_session_handler(arguments: dict[str, Any]) -> Any:
    return desktop_agent.stop_session(_session_lookup(arguments))


def _session_logs_handler(arguments: dict[str, Any]) -> Any:
    return desktop_agent.get_session_logs(_session_lookup(arguments))


def _session_state_handler(arguments: dict[str, Any]) -> Any:
    return desktop_agent.get_session_state(_session_lookup(arguments))


def _session_command_handler(arguments: dict[str, Any]) -> Any:
    session_id = _session_lookup(arguments)
    payload = desktop_agent.SessionCommandRequest(
        command=arguments.get("command"),
        params=arguments.get("params") or {},
    )
    return desktop_agent.run_session_command(session_id, payload)


TOOLS: dict[str, ToolDefinition] = {
    "health": ToolDefinition(
        name="health",
        description="Return desktop agent health and screen metadata.",
        input_schema=_empty_schema(),
        handler=_plain_tool(desktop_agent.health),
    ),
    "screen_info": ToolDefinition(
        name="screen_info",
        description="Return monitor bounds and current mouse position.",
        input_schema=_empty_schema(),
        handler=_plain_tool(desktop_agent.screen_info),
    ),
    "screenshot": ToolDefinition(
        name="screenshot",
        description="Capture a screenshot and return it as base64.",
        input_schema={
            "type": "object",
            "properties": {
                "monitor": {"type": "integer", "minimum": 0},
                "format": {"type": "string", "enum": ["png", "jpeg"]},
                "quality": {"type": "integer", "minimum": 1, "maximum": 100},
                "max_width": {"type": "integer", "minimum": 1, "maximum": 10000},
                "x": {"type": "integer", "minimum": 0},
                "y": {"type": "integer", "minimum": 0},
                "width": {"type": "integer", "minimum": 1},
                "height": {"type": "integer", "minimum": 1},
                "save_to_disk": {"type": "boolean"},
                "annotate_bounds": {"type": "boolean"},
            },
            "additionalProperties": False,
        },
        handler=_model_tool(desktop_agent.ScreenshotRequest, desktop_agent.screenshot),
    ),
    "mouse_position": ToolDefinition(
        name="mouse_position",
        description="Return the current mouse coordinates.",
        input_schema=_empty_schema(),
        handler=_plain_tool(desktop_agent.mouse_position),
    ),
    "move_mouse": ToolDefinition(
        name="move_mouse",
        description="Move the mouse to a screen coordinate.",
        input_schema={
            "type": "object",
            "properties": {
                "x": {"type": "integer", "minimum": 0},
                "y": {"type": "integer", "minimum": 0},
                "duration": {"type": "number", "minimum": 0.0, "maximum": 5.0},
            },
            "required": ["x", "y"],
            "additionalProperties": False,
        },
        handler=_model_tool(desktop_agent.Point, desktop_agent.move_mouse),
    ),
    "click": ToolDefinition(
        name="click",
        description="Click at a screen coordinate.",
        input_schema={
            "type": "object",
            "properties": {
                "x": {"type": "integer", "minimum": 0},
                "y": {"type": "integer", "minimum": 0},
                "duration": {"type": "number", "minimum": 0.0, "maximum": 5.0},
                "button": {"type": "string", "enum": ["left", "middle", "right"]},
                "clicks": {"type": "integer", "minimum": 1, "maximum": 5},
                "interval": {"type": "number", "minimum": 0.0, "maximum": 1.0},
            },
            "required": ["x", "y"],
            "additionalProperties": False,
        },
        handler=_model_tool(desktop_agent.ClickRequest, desktop_agent.click),
    ),
    "drag_mouse": ToolDefinition(
        name="drag_mouse",
        description="Drag the mouse from one coordinate to another.",
        input_schema={
            "type": "object",
            "properties": {
                "start_x": {"type": "integer", "minimum": 0},
                "start_y": {"type": "integer", "minimum": 0},
                "end_x": {"type": "integer", "minimum": 0},
                "end_y": {"type": "integer", "minimum": 0},
                "duration": {"type": "number", "minimum": 0.0, "maximum": 10.0},
                "button": {"type": "string", "enum": ["left", "middle", "right"]},
            },
            "required": ["start_x", "start_y", "end_x", "end_y"],
            "additionalProperties": False,
        },
        handler=_model_tool(desktop_agent.DragRequest, desktop_agent.drag_mouse),
    ),
    "scroll": ToolDefinition(
        name="scroll",
        description="Scroll the mouse wheel optionally after moving to a coordinate.",
        input_schema={
            "type": "object",
            "properties": {
                "clicks": {"type": "integer", "minimum": -5000, "maximum": 5000},
                "x": {"type": "integer", "minimum": 0},
                "y": {"type": "integer", "minimum": 0},
            },
            "required": ["clicks"],
            "additionalProperties": False,
        },
        handler=_model_tool(desktop_agent.ScrollRequest, desktop_agent.scroll),
    ),
    "type_text": ToolDefinition(
        name="type_text",
        description="Type text into the active window.",
        input_schema={
            "type": "object",
            "properties": {
                "text": {"type": "string", "minLength": 1, "maxLength": 5000},
                "interval": {"type": "number", "minimum": 0.0, "maximum": 1.0},
                "press_enter": {"type": "boolean"},
            },
            "required": ["text"],
            "additionalProperties": False,
        },
        handler=_model_tool(desktop_agent.TypeRequest, desktop_agent.type_text),
    ),
    "press_hotkey": ToolDefinition(
        name="press_hotkey",
        description="Press a hotkey combination.",
        input_schema={
            "type": "object",
            "properties": {
                "keys": {"type": "array", "items": {"type": "string"}, "minItems": 1, "maxItems": 5},
            },
            "required": ["keys"],
            "additionalProperties": False,
        },
        handler=_model_tool(desktop_agent.HotkeyRequest, desktop_agent.press_hotkey),
    ),
    "list_windows": ToolDefinition(
        name="list_windows",
        description="List visible windows and their bounds.",
        input_schema=_empty_schema(),
        handler=_plain_tool(desktop_agent.list_windows),
    ),
    "activate_window": ToolDefinition(
        name="activate_window",
        description="Activate a window by title match.",
        input_schema={
            "type": "object",
            "properties": {
                "title": {"type": "string", "minLength": 1, "maxLength": 500},
                "exact": {"type": "boolean"},
                "restore_if_minimized": {"type": "boolean"},
                "best_effort": {"type": "boolean"},
            },
            "required": ["title"],
            "additionalProperties": False,
        },
        handler=_model_tool(desktop_agent.WindowMatchRequest, desktop_agent.activate_window),
    ),
    "screenshot_window": ToolDefinition(
        name="screenshot_window",
        description="Capture a screenshot of a specific window, optionally cropped and saved to disk.",
        input_schema={
            "type": "object",
            "properties": {
                "title": {"type": "string", "minLength": 1, "maxLength": 500},
                "exact": {"type": "boolean"},
                "format": {"type": "string", "enum": ["png", "jpeg"]},
                "quality": {"type": "integer", "minimum": 1, "maximum": 100},
                "x": {"type": "integer", "minimum": 0},
                "y": {"type": "integer", "minimum": 0},
                "width": {"type": "integer", "minimum": 1},
                "height": {"type": "integer", "minimum": 1},
                "save_to_disk": {"type": "boolean"},
                "annotate_bounds": {"type": "boolean"},
            },
            "required": ["title"],
            "additionalProperties": False,
        },
        handler=_model_tool(desktop_agent.WindowScreenshotRequest, desktop_agent.screenshot_window),
    ),
    "list_sessions": ToolDefinition(
        name="list_sessions",
        description="List active Number Munchers evaluation sessions.",
        input_schema=_empty_schema(),
        handler=_plain_tool(desktop_agent.list_sessions),
    ),
    "start_session": ToolDefinition(
        name="start_session",
        description="Launch Number Munchers and wait for its debug API to become ready.",
        input_schema={
            "type": "object",
            "properties": {
                "command": {"type": "array", "items": {"type": "string"}},
                "debug_port": {"type": "integer", "minimum": 1, "maximum": 65535},
                "seed": {"type": "integer"},
                "startup_timeout_seconds": {"type": "number", "minimum": 1.0, "maximum": 120.0},
                "capture_logs": {"type": "boolean"},
                "wait_for": {
                    "type": "array",
                    "items": {
                        "type": "string",
                        "enum": ["debug_api", "window_visible", "board_initialized", "state_stable"],
                    },
                },
            },
            "additionalProperties": False,
        },
        handler=_model_tool(desktop_agent.StartSessionRequest, desktop_agent.start_session),
    ),
    "get_session": ToolDefinition(
        name="get_session",
        description="Get session status and recent log tails.",
        input_schema={
            "type": "object",
            "properties": {"session_id": {"type": "string", "minLength": 1}},
            "required": ["session_id"],
            "additionalProperties": False,
        },
        handler=_get_session_handler,
    ),
    "stop_session": ToolDefinition(
        name="stop_session",
        description="Stop a running Number Munchers session.",
        input_schema={
            "type": "object",
            "properties": {"session_id": {"type": "string", "minLength": 1}},
            "required": ["session_id"],
            "additionalProperties": False,
        },
        handler=_stop_session_handler,
    ),
    "get_session_logs": ToolDefinition(
        name="get_session_logs",
        description="Read recent stdout and stderr log output for a session.",
        input_schema={
            "type": "object",
            "properties": {"session_id": {"type": "string", "minLength": 1}},
            "required": ["session_id"],
            "additionalProperties": False,
        },
        handler=_session_logs_handler,
    ),
    "get_session_state": ToolDefinition(
        name="get_session_state",
        description="Read structured game state for a session from the JavaFX debug API.",
        input_schema={
            "type": "object",
            "properties": {"session_id": {"type": "string", "minLength": 1}},
            "required": ["session_id"],
            "additionalProperties": False,
        },
        handler=_session_state_handler,
    ),
    "get_session_health": ToolDefinition(
        name="get_session_health",
        description="Read structured health for a session including PID and port ownership checks.",
        input_schema={
            "type": "object",
            "properties": {"session_id": {"type": "string", "minLength": 1}},
            "required": ["session_id"],
            "additionalProperties": False,
        },
        handler=lambda arguments: desktop_agent.get_session_health(_session_lookup(arguments)),
    ),
    "run_session_command": ToolDefinition(
        name="run_session_command",
        description="Send a structured command to the JavaFX debug API for a session.",
        input_schema={
            "type": "object",
            "properties": {
                "session_id": {"type": "string", "minLength": 1},
                "command": {
                    "type": "string",
                    "enum": ["state", "reset", "set-seed", "move", "munch", "pause", "resume", "toggle-pause", "tick-enemies"],
                },
                "params": {"type": "object", "additionalProperties": {"type": "string"}},
            },
            "required": ["session_id", "command"],
            "additionalProperties": False,
        },
        handler=_session_command_handler,
    ),
}


class MCPServer:
    def run(self) -> None:
        _debug_log("mcp_server.run: start")
        while True:
            message = self._read_message()
            if message is None:
                _debug_log("mcp_server.run: stdin closed")
                return
            try:
                self._handle_message(message)
            except Exception as exc:  # pragma: no cover - defensive transport fallback
                _debug_log(f"mcp_server.run: unhandled exception {exc!r}")
                request_id = message.get("id")
                if request_id is not None:
                    self._write_message(
                        {
                            "jsonrpc": "2.0",
                            "id": request_id,
                            "error": {
                                "code": -32603,
                                "message": str(exc),
                                "data": {"traceback": traceback.format_exc()},
                            },
                        }
                    )

    def _read_message(self) -> JSON | None:
        _debug_log("mcp_server._read_message: waiting for headers")
        headers: dict[str, str] = {}
        while True:
            line = sys.stdin.buffer.readline()
            if not line:
                _debug_log("mcp_server._read_message: eof")
                return None
            if line in (b"\r\n", b"\n"):
                break
            key, _, value = line.decode("utf-8").partition(":")
            headers[key.strip().lower()] = value.strip()

        content_length = headers.get("content-length")
        if content_length is None:
            raise RuntimeError("Missing Content-Length header.")

        body = sys.stdin.buffer.read(int(content_length))
        _debug_log(f"mcp_server._read_message: received body bytes={len(body)}")
        return json.loads(body.decode("utf-8"))

    def _write_message(self, payload: JSON) -> None:
        body = json.dumps(payload, ensure_ascii=True).encode("utf-8")
        sys.stdout.buffer.write(f"Content-Length: {len(body)}\r\n\r\n".encode("ascii"))
        sys.stdout.buffer.write(body)
        sys.stdout.buffer.flush()
        _debug_log(
            "mcp_server._write_message: wrote message "
            + str(payload.get("method") or payload.get("id") or "unknown")
        )

    def _handle_message(self, message: JSON) -> None:
        method = message.get("method")
        request_id = message.get("id")
        params = message.get("params") or {}
        _debug_log(f"mcp_server._handle_message: method={method!r} id={request_id!r}")

        if request_id is None:
            if method == "notifications/initialized":
                return
            return

        if method == "initialize":
            client_version = params.get("protocolVersion")
            protocol_version = client_version if client_version in PROTOCOL_VERSIONS else PROTOCOL_VERSIONS[0]
            self._write_message(
                {
                    "jsonrpc": "2.0",
                    "id": request_id,
                    "result": {
                        "protocolVersion": protocol_version,
                        "capabilities": {"tools": {}},
                        "serverInfo": {"name": "desktop-agent-mcp", "version": "0.1.0"},
                    },
                }
            )
            return

        if method == "ping":
            self._write_message({"jsonrpc": "2.0", "id": request_id, "result": {}})
            return

        if method == "tools/list":
            self._write_message(
                {
                    "jsonrpc": "2.0",
                    "id": request_id,
                    "result": {
                        "tools": [
                            {
                                "name": tool.name,
                                "description": tool.description,
                                "inputSchema": tool.input_schema,
                            }
                            for tool in TOOLS.values()
                        ]
                    },
                }
            )
            return

        if method == "tools/call":
            name = params.get("name")
            arguments = params.get("arguments") or {}
            tool = TOOLS.get(name)
            if tool is None:
                self._write_message(
                    {
                        "jsonrpc": "2.0",
                        "id": request_id,
                        "result": _json_result({"error": f"Unknown tool: {name}"}, is_error=True),
                    }
                )
                return

            try:
                value = tool.handler(arguments)
                result = _json_result(value)
            except HTTPException as exc:
                result = _json_result({"error": exc.detail, "status_code": exc.status_code}, is_error=True)
            except ValidationError as exc:
                result = _json_result({"error": exc.errors()}, is_error=True)
            except Exception as exc:  # pragma: no cover - defensive runtime boundary
                result = _json_result({"error": str(exc), "traceback": traceback.format_exc()}, is_error=True)

            self._write_message({"jsonrpc": "2.0", "id": request_id, "result": result})
            return

        self._write_message(
            {
                "jsonrpc": "2.0",
                "id": request_id,
                "error": {"code": -32601, "message": f"Method not found: {method}"},
            }
        )


def main() -> None:
    _debug_log("mcp_server.main: entered")
    MCPServer().run()


if __name__ == "__main__":
    main()
