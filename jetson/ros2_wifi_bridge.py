#!/usr/bin/env python3
import base64
import hashlib
import hmac
import json
import os
import threading
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import rclpy
from geometry_msgs.msg import Twist
from rclpy.executors import ExternalShutdownException
from rclpy.node import Node
from sensor_msgs.msg import BatteryState
from std_msgs.msg import Float32, Int32, String


BRIDGE_VERSION = "wifi-http-v4-five-step-presets"
HOST = os.environ.get("WHEELCHAIR_API_HOST", "0.0.0.0")
PORT = int(os.environ.get("WHEELCHAIR_API_PORT", "8081"))
TWIST_SPEED_TOPIC = os.environ.get("WHEELCHAIR_TWIST_SPEED_TOPIC", "/cmd_vel")
DATA_DIR = Path(os.environ.get("WHEELCHAIR_DATA_DIR", str(Path.home() / ".wheelchair_monitor")))
USERS_FILE = DATA_DIR / "users.json"
PASSWORD_ITERATIONS = 120_000
PRESET_IDS = ("base", "fast", "slow")
ALLOWED_PRESET_MODES = ("manual", "assist", "autonomous")


def default_presets():
    return [
        {"id": "base", "name": "Base 모드", "mode": "assist", "step_level": 3},
        {"id": "fast", "name": "쾌속 모드", "mode": "autonomous", "step_level": 5},
        {"id": "slow", "name": "저속 모드", "mode": "manual", "step_level": 1},
    ]


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def password_record(password):
    salt = os.urandom(16)
    digest = hashlib.pbkdf2_hmac(
        "sha256",
        password.encode("utf-8"),
        salt,
        PASSWORD_ITERATIONS,
    )
    return {
        "password_salt": base64.b64encode(salt).decode("ascii"),
        "password_hash": base64.b64encode(digest).decode("ascii"),
        "password_iterations": PASSWORD_ITERATIONS,
    }


def verify_password(password, user):
    try:
        salt = base64.b64decode(user["password_salt"])
        expected = base64.b64decode(user["password_hash"])
        iterations = int(user.get("password_iterations", PASSWORD_ITERATIONS))
    except (KeyError, ValueError):
        return False

    actual = hashlib.pbkdf2_hmac(
        "sha256",
        password.encode("utf-8"),
        salt,
        iterations,
    )
    return hmac.compare_digest(actual, expected)


def public_user(user):
    result = dict(user)
    result.pop("password", None)
    result.pop("password_hash", None)
    result.pop("password_salt", None)
    result.pop("password_iterations", None)
    return result


def normalize_mode(value, fallback="assist"):
    mode = str(value or "").strip().lower()
    if mode in ALLOWED_PRESET_MODES:
        return mode
    return fallback


def normalize_step_level(value, fallback=3):
    try:
        step_level = int(value)
    except (TypeError, ValueError):
        return fallback
    return max(1, min(5, step_level))


def normalize_preset(preset, fallback):
    name = str(preset.get("name", fallback["name"])).strip()
    if not name:
        name = fallback["name"]
    return {
        "id": fallback["id"],
        "name": name[:32],
        "mode": normalize_mode(preset.get("mode"), fallback["mode"]),
        "step_level": normalize_step_level(preset.get("step_level"), fallback["step_level"]),
    }


def normalize_presets(raw_presets):
    raw_by_id = {}
    if isinstance(raw_presets, list):
        for preset in raw_presets:
            if isinstance(preset, dict):
                raw_by_id[str(preset.get("id", "")).strip()] = preset

    presets = []
    for fallback in default_presets():
        presets.append(normalize_preset(raw_by_id.get(fallback["id"], {}), fallback))
    return presets


class UserStore:
    def __init__(self, path):
        self.path = path
        self.lock = threading.Lock()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        if not self.path.exists():
            self.write_data({
                "users": {},
                "login_logs": [],
                "usage_logs": [],
            })

    def read_data(self):
        try:
            return json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return {"users": {}, "login_logs": [], "usage_logs": []}

    def write_data(self, data):
        self.path.write_text(
            json.dumps(data, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    def register(self, profile):
        user_id = str(profile.get("user_id", "")).strip()
        password = str(profile.get("password", ""))
        name = str(profile.get("name", "")).strip()
        if not user_id or not password or not name:
            raise ValueError("user_id, password and name are required")

        with self.lock:
            data = self.read_data()
            users = data.setdefault("users", {})
            existing = users.get(user_id, {})
            if existing:
                raise ValueError("user_id already exists")

            profile = dict(profile)
            profile["user_id"] = user_id
            profile["name"] = name
            profile.pop("password", None)
            profile.update(password_record(password))
            profile["updated_at"] = utc_now()
            profile["created_at"] = existing.get("created_at", profile["updated_at"])
            users[user_id] = profile
            data.setdefault("usage_logs", []).append({
                "type": "register",
                "user_id": user_id,
                "time": utc_now(),
            })
            self.write_data(data)
            return public_user(profile)

    def login(self, user_id, password):
        user_id = str(user_id).strip()
        password = str(password)
        if not user_id or not password:
            raise ValueError("user_id and password are required")

        with self.lock:
            data = self.read_data()
            user = data.setdefault("users", {}).get(user_id)
            if user is None:
                raise KeyError("user not found")
            if not verify_password(password, user):
                raise PermissionError("invalid password")
            data.setdefault("login_logs", []).append({
                "user_id": user_id,
                "time": utc_now(),
            })
            data.setdefault("usage_logs", []).append({
                "type": "login",
                "user_id": user_id,
                "time": utc_now(),
            })
            self.write_data(data)
            return public_user(user)

    def log_usage(self, event):
        with self.lock:
            data = self.read_data()
            entry = dict(event)
            entry["time"] = utc_now()
            data.setdefault("usage_logs", []).append(entry)
            self.write_data(data)
            return entry

    def logs_for(self, user_id):
        with self.lock:
            data = self.read_data()
            return {
                "login_logs": [
                    log for log in data.get("login_logs", [])
                    if log.get("user_id") == user_id
                ],
                "usage_logs": [
                    log for log in data.get("usage_logs", [])
                    if log.get("user_id") == user_id
                ],
            }

    def presets_for(self, user_id):
        user_id = str(user_id).strip()
        if not user_id:
            raise ValueError("user_id is required")

        with self.lock:
            data = self.read_data()
            user = data.setdefault("users", {}).get(user_id)
            if user is None:
                raise KeyError("user not found")

            presets = normalize_presets(user.get("presets"))
            default_preset_id = str(user.get("default_preset_id", "")).strip()
            if default_preset_id not in PRESET_IDS:
                default_preset_id = ""
            return {
                "user_id": user_id,
                "presets": presets,
                "default_preset_id": default_preset_id,
            }

    def save_presets(self, payload):
        user_id = str(payload.get("user_id", "")).strip()
        if not user_id:
            raise ValueError("user_id is required")

        presets = normalize_presets(payload.get("presets"))
        default_preset_id = str(payload.get("default_preset_id", "")).strip()
        if default_preset_id not in PRESET_IDS:
            default_preset_id = ""

        with self.lock:
            data = self.read_data()
            user = data.setdefault("users", {}).get(user_id)
            if user is None:
                raise KeyError("user not found")

            user["presets"] = presets
            user["default_preset_id"] = default_preset_id
            user["updated_at"] = utc_now()
            data.setdefault("usage_logs", []).append({
                "type": "save_presets",
                "user_id": user_id,
                "default_preset_id": default_preset_id,
                "time": utc_now(),
            })
            self.write_data(data)
            return {
                "user_id": user_id,
                "presets": presets,
                "default_preset_id": default_preset_id,
            }


class WheelchairWifiBridge(Node):
    def __init__(self):
        super().__init__("wheelchair_wifi_bridge")
        self.get_logger().info(f"Wheelchair Wi-Fi bridge version: {BRIDGE_VERSION}")
        self.telemetry_lock = threading.Lock()
        self.telemetry = {
            "speed_kph": None,
            "battery_percent": None,
            "mode": None,
            "updated_at": None,
        }
        self.user_store = UserStore(USERS_FILE)
        self.http_server = None
        self.http_thread = None

        self.mode_command_pub = self.create_publisher(String, "/wheelchair/mode_command", 10)
        self.step_command_pub = self.create_publisher(Int32, "/wheelchair/step_command", 10)
        self.stop_command_pub = self.create_publisher(String, "/wheelchair/stop_command", 10)

        self.create_subscription(Float32, "/wheelchair/speed_mps", self.on_speed_mps, 10)
        self.create_subscription(Float32, "/wheelchair/speed_kph", self.on_speed_kph, 10)
        self.create_subscription(Twist, TWIST_SPEED_TOPIC, self.on_twist_speed, 10)
        self.create_subscription(Int32, "/wheelchair/battery_percent", self.on_battery_percent, 10)
        self.create_subscription(BatteryState, "/battery/soc", self.on_battery_state, 10)
        self.create_subscription(String, "/wheelchair/mode", self.on_mode, 10)

        self.start_http_server()

    def destroy_node(self):
        if self.http_server is not None:
            self.http_server.shutdown()
            self.http_server.server_close()
        super().destroy_node()

    def update_telemetry(self, **values):
        with self.telemetry_lock:
            self.telemetry.update(values)
            self.telemetry["updated_at"] = utc_now()

    def telemetry_snapshot(self):
        with self.telemetry_lock:
            return dict(self.telemetry)

    def on_speed_mps(self, msg):
        self.update_telemetry(speed_kph=float(msg.data) * 3.6)

    def on_speed_kph(self, msg):
        self.update_telemetry(speed_kph=float(msg.data))

    def on_twist_speed(self, msg):
        self.update_telemetry(speed_kph=float(msg.linear.x) * 3.6)

    def on_battery_percent(self, msg):
        self.update_telemetry(battery_percent=int(msg.data))

    def on_battery_state(self, msg):
        self.update_telemetry(battery_percent=round(float(msg.percentage) * 100.0))

    def on_mode(self, msg):
        self.update_telemetry(mode=msg.data)

    def command_log_entry(self, payload, event_type, **values):
        entry = {
            "type": event_type,
            "source": payload.get("source", "android"),
        }
        user_id = str(payload.get("user_id", "")).strip()
        if user_id:
            entry["user_id"] = user_id
        entry.update(values)
        return entry

    def handle_command(self, payload):
        command = payload.get("command")
        if command == "set_mode":
            mode = str(payload.get("mode", "")).strip()
            if not mode:
                raise ValueError("mode is required")
            msg = String()
            msg.data = mode
            self.mode_command_pub.publish(msg)
            self.user_store.log_usage(self.command_log_entry(payload, "set_mode", mode=mode))
            return {"ok": True, "published": "/wheelchair/mode_command", "mode": mode}

        if command == "set_step":
            step_level = int(payload.get("step_level"))
            if step_level < 1 or step_level > 5:
                raise ValueError("step_level must be 1..5")
            msg = Int32()
            msg.data = step_level
            self.step_command_pub.publish(msg)
            self.user_store.log_usage(self.command_log_entry(payload, "set_step", step_level=step_level))
            return {"ok": True, "published": "/wheelchair/step_command", "step_level": step_level}

        if command == "apply_preset":
            preset_id = str(payload.get("preset_id", "")).strip()
            preset_name = str(payload.get("preset_name", "")).strip()
            mode = normalize_mode(payload.get("mode"), "assist")
            step_level = normalize_step_level(payload.get("step_level"), 3)

            mode_msg = String()
            mode_msg.data = mode
            self.mode_command_pub.publish(mode_msg)

            step_msg = Int32()
            step_msg.data = step_level
            self.step_command_pub.publish(step_msg)

            self.user_store.log_usage(self.command_log_entry(
                payload,
                "apply_preset",
                preset_id=preset_id,
                preset_name=preset_name,
                mode=mode,
                step_level=step_level,
            ))
            return {
                "ok": True,
                "published": ["/wheelchair/mode_command", "/wheelchair/step_command"],
                "preset_id": preset_id,
                "preset_name": preset_name,
                "mode": mode,
                "step_level": step_level,
            }

        if command == "stop":
            msg = String()
            msg.data = "stop"
            self.stop_command_pub.publish(msg)
            self.user_store.log_usage(self.command_log_entry(payload, "stop"))
            return {"ok": True, "published": "/wheelchair/stop_command"}

        raise ValueError(f"unknown command: {command}")

    def start_http_server(self):
        handler = make_handler(self)
        self.http_server = ThreadingHTTPServer((HOST, PORT), handler)
        self.http_thread = threading.Thread(target=self.http_server.serve_forever, daemon=True)
        self.http_thread.start()
        self.get_logger().info(f"Wi-Fi HTTP API ready: http://{HOST}:{PORT}/api/health")


def make_handler(node):
    class WheelchairApiHandler(BaseHTTPRequestHandler):
        def do_OPTIONS(self):
            self.send_response(204)
            self.send_cors_headers()
            self.end_headers()

        def do_GET(self):
            parsed = urlparse(self.path)
            if parsed.path == "/api/health":
                self.send_json(200, {
                    "ok": True,
                    "version": BRIDGE_VERSION,
                    "time": utc_now(),
                })
                return

            if parsed.path == "/api/telemetry":
                self.send_json(200, {
                    "ok": True,
                    "telemetry": node.telemetry_snapshot(),
                })
                return

            if parsed.path == "/api/users/logs":
                user_id = parse_qs(parsed.query).get("user_id", [""])[0]
                self.send_json(200, {
                    "ok": True,
                    "logs": node.user_store.logs_for(user_id),
                })
                return

            if parsed.path == "/api/users/presets":
                user_id = parse_qs(parsed.query).get("user_id", [""])[0]
                self.send_json(200, {
                    "ok": True,
                    "presets_data": node.user_store.presets_for(user_id),
                })
                return

            self.send_json(404, {"ok": False, "error": "not found"})

        def do_POST(self):
            try:
                payload = self.read_json_body()
                if self.path == "/api/command":
                    self.send_json(200, node.handle_command(payload))
                    return

                if self.path == "/api/users/register":
                    user = node.user_store.register(payload)
                    self.send_json(200, {"ok": True, "user": user})
                    return

                if self.path == "/api/users/login":
                    user = node.user_store.login(
                        payload.get("user_id", ""),
                        payload.get("password", ""),
                    )
                    self.send_json(200, {"ok": True, "user": user})
                    return

                if self.path == "/api/users/usage":
                    entry = node.user_store.log_usage(payload)
                    self.send_json(200, {"ok": True, "entry": entry})
                    return

                if self.path == "/api/users/presets":
                    presets_data = node.user_store.save_presets(payload)
                    self.send_json(200, {"ok": True, "presets_data": presets_data})
                    return

                self.send_json(404, {"ok": False, "error": "not found"})
            except KeyError as exc:
                self.send_json(404, {"ok": False, "error": str(exc)})
            except PermissionError as exc:
                self.send_json(401, {"ok": False, "error": str(exc)})
            except (ValueError, TypeError, json.JSONDecodeError) as exc:
                self.send_json(400, {"ok": False, "error": str(exc)})

        def read_json_body(self):
            length = int(self.headers.get("Content-Length", "0"))
            raw_body = self.rfile.read(length).decode("utf-8")
            if not raw_body:
                return {}
            return json.loads(raw_body)

        def send_json(self, status_code, payload):
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status_code)
            self.send_cors_headers()
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def send_cors_headers(self):
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Allow-Headers", "Content-Type")
            self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")

        def log_message(self, format, *args):
            return

    return WheelchairApiHandler


def main():
    rclpy.init()
    node = WheelchairWifiBridge()
    try:
        rclpy.spin(node)
    except (KeyboardInterrupt, ExternalShutdownException):
        pass
    finally:
        node.destroy_node()
        if rclpy.ok():
            rclpy.shutdown()


if __name__ == "__main__":
    main()
