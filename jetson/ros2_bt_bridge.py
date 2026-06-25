#!/usr/bin/env python3
import json
import os
import socket
import threading
import time

import rclpy
from geometry_msgs.msg import Twist
from rclpy.executors import ExternalShutdownException
from rclpy.node import Node
from sensor_msgs.msg import BatteryState
from std_msgs.msg import Float32, Int32, String


BRIDGE_VERSION = "no-pybluez-stdlib-socket-v4-twist-speed"
RFCOMM_CHANNEL = int(os.environ.get("WHEELCHAIR_RFCOMM_CHANNEL", "1"))
RFCOMM_BIND_ADDRESS = os.environ.get("WHEELCHAIR_BT_ADDRESS", "00:00:00:00:00:00")
TWIST_SPEED_TOPIC = os.environ.get("WHEELCHAIR_TWIST_SPEED_TOPIC", "/cmd_vel")
SOCKET_TIMEOUT_SEC = 1.0
SERVER_RETRY_DELAY_SEC = 2.0


class WheelchairBluetoothBridge(Node):
    def __init__(self):
        super().__init__("wheelchair_bluetooth_bridge")
        self.get_logger().info(f"Wheelchair Bluetooth bridge version: {BRIDGE_VERSION}")
        self.client_socket = None
        self.server_socket = None
        self.socket_lock = threading.Lock()
        self.running = True

        self.mode_command_pub = self.create_publisher(
            String,
            "/wheelchair/mode_command",
            10,
        )
        self.step_command_pub = self.create_publisher(
            Int32,
            "/wheelchair/step_command",
            10,
        )
        self.stop_command_pub = self.create_publisher(
            String,
            "/wheelchair/stop_command",
            10,
        )

        self.create_subscription(Float32, "/wheelchair/speed_mps", self.on_speed_mps, 10)
        self.create_subscription(Float32, "/wheelchair/speed_kph", self.on_speed_kph, 10)
        self.create_subscription(Twist, TWIST_SPEED_TOPIC, self.on_twist_speed, 10)
        self.create_subscription(Int32, "/wheelchair/battery_percent", self.on_battery_percent, 10)
        self.create_subscription(BatteryState, "/battery/soc", self.on_battery_state, 10)
        self.create_subscription(String, "/wheelchair/mode", self.on_mode, 10)

        self.bluetooth_thread = threading.Thread(target=self.bluetooth_server_loop, daemon=True)
        self.bluetooth_thread.start()

    def destroy_node(self):
        self.running = False
        self.close_client_socket()
        self.close_server_socket()
        super().destroy_node()

    def on_speed_mps(self, msg):
        self.send_json({"speed_mps": msg.data})

    def on_speed_kph(self, msg):
        self.send_json({"speed_kph": msg.data})

    def on_twist_speed(self, msg):
        self.send_json({"speed_mps": msg.linear.x})

    def on_battery_percent(self, msg):
        self.send_json({"battery_percent": msg.data})

    def on_battery_state(self, msg):
        self.send_json({
            "op": "publish",
            "topic": "/battery/soc",
            "msg": {"percentage": msg.percentage},
        })

    def on_mode(self, msg):
        self.send_json({"mode": msg.data})

    def send_json(self, payload):
        line = json.dumps(payload, ensure_ascii=False)
        with self.socket_lock:
            client_socket = self.client_socket
            if client_socket is None:
                return

        try:
            client_socket.sendall((line + "\n").encode("utf-8"))
        except OSError as exc:
            self.get_logger().warn(f"Bluetooth send failed: {exc}")
            self.close_client_socket(client_socket)

    def bluetooth_server_loop(self):
        if not hasattr(socket, "AF_BLUETOOTH") or not hasattr(socket, "BTPROTO_RFCOMM"):
            self.get_logger().error(
                "This Python build does not support AF_BLUETOOTH/BTPROTO_RFCOMM."
            )
            return

        while self.running:
            try:
                self.run_bluetooth_server_once()
            except Exception as exc:  # Keep the ROS bridge alive on unexpected socket errors.
                self.get_logger().error(f"Bluetooth server crashed, restarting: {exc}")
            finally:
                self.close_client_socket()
                self.close_server_socket()

            if self.running:
                time.sleep(SERVER_RETRY_DELAY_SEC)

    def run_bluetooth_server_once(self):
        server_socket = socket.socket(
            socket.AF_BLUETOOTH,
            socket.SOCK_STREAM,
            socket.BTPROTO_RFCOMM,
        )
        server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server_socket.settimeout(SOCKET_TIMEOUT_SEC)
        try:
            server_socket.bind((RFCOMM_BIND_ADDRESS, RFCOMM_CHANNEL))
        except OSError as exc:
            self.get_logger().error(
                f"Bluetooth bind failed for address {RFCOMM_BIND_ADDRESS} "
                f"channel {RFCOMM_CHANNEL}: {exc}"
            )
            self.get_logger().error(
                "If this continues, set WHEELCHAIR_BT_ADDRESS to your adapter "
                "address from: cat /sys/class/bluetooth/hci0/address"
            )
            server_socket.close()
            return
        server_socket.listen(1)
        with self.socket_lock:
            self.server_socket = server_socket

        port = server_socket.getsockname()[1]
        self.get_logger().info(
            f"Bluetooth RFCOMM server ready on channel {port}. "
            "No SDP advertising is used."
        )

        while self.running:
            try:
                client_socket, client_info = server_socket.accept()
            except socket.timeout:
                continue
            except OSError:
                return

            client_socket.settimeout(SOCKET_TIMEOUT_SEC)
            self.get_logger().info(f"Android connected: {client_info}")
            with self.socket_lock:
                if self.client_socket is not None:
                    self.close_socket(self.client_socket)
                self.client_socket = client_socket

            self.read_commands(client_socket)

            self.close_client_socket(client_socket)
            self.get_logger().info("Android disconnected")

    def read_commands(self, client_socket):
        buffer = ""
        while self.running:
            if not self.is_current_client(client_socket):
                return
            try:
                data = client_socket.recv(1024)
            except socket.timeout:
                continue
            except OSError as exc:
                self.get_logger().warn(f"Bluetooth receive failed: {exc}")
                return
            if not data:
                return

            buffer += data.decode("utf-8", errors="replace")
            while "\n" in buffer:
                line, buffer = buffer.split("\n", 1)
                self.handle_command(line.strip())

    def handle_command(self, line):
        if not line:
            return
        try:
            payload = json.loads(line)
        except json.JSONDecodeError:
            self.get_logger().warn(f"Invalid command JSON: {line}")
            return

        command = payload.get("command")
        if command == "set_mode":
            self.handle_mode_command(payload)
        elif command == "set_step":
            self.handle_step_command(payload)
        elif command == "stop":
            self.handle_stop_command()
        else:
            self.get_logger().warn(f"Unknown command: {payload}")
            return

    def handle_mode_command(self, payload):
        mode = str(payload.get("mode", "")).strip()
        if not mode:
            self.get_logger().warn(f"Missing mode in command: {payload}")
            return

        msg = String()
        msg.data = mode
        self.mode_command_pub.publish(msg)
        self.get_logger().info(f"Published mode command: {mode}")

    def handle_step_command(self, payload):
        try:
            step_level = int(payload.get("step_level"))
        except (TypeError, ValueError):
            self.get_logger().warn(f"Missing step_level in command: {payload}")
            return
        if step_level < 1 or step_level > 3:
            self.get_logger().warn(f"Invalid step_level in command: {payload}")
            return

        msg = Int32()
        msg.data = step_level
        self.step_command_pub.publish(msg)
        self.get_logger().info(f"Published step command: {step_level}")

    def handle_stop_command(self):
        msg = String()
        msg.data = "stop"
        self.stop_command_pub.publish(msg)
        self.get_logger().info("Published stop command")

    def is_current_client(self, client_socket):
        with self.socket_lock:
            return self.client_socket is client_socket

    def close_client_socket(self, expected_socket=None):
        with self.socket_lock:
            if expected_socket is not None and self.client_socket is not expected_socket:
                return
            client_socket = self.client_socket
            self.client_socket = None
        self.close_socket(client_socket)

    def close_server_socket(self):
        with self.socket_lock:
            server_socket = self.server_socket
            self.server_socket = None
        self.close_socket(server_socket)

    def close_socket(self, socket_to_close):
        if socket_to_close is None:
            return
        try:
            socket_to_close.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            socket_to_close.close()
        except OSError:
            pass


def main():
    rclpy.init()
    node = WheelchairBluetoothBridge()
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
