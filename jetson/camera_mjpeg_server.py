#!/usr/bin/env python3
import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import cv2
import numpy as np


HOST = os.environ.get("WHEELCHAIR_CAMERA_HOST", "0.0.0.0")
PORT = int(os.environ.get("WHEELCHAIR_CAMERA_PORT", "8080"))
SOURCE = os.environ.get("WHEELCHAIR_CAMERA_SOURCE", "0")
BACKEND = os.environ.get("WHEELCHAIR_CAMERA_BACKEND", "default").lower()
WIDTH = int(os.environ.get("WHEELCHAIR_CAMERA_WIDTH", "640"))
HEIGHT = int(os.environ.get("WHEELCHAIR_CAMERA_HEIGHT", "480"))
FPS = int(os.environ.get("WHEELCHAIR_CAMERA_FPS", "15"))
JPEG_QUALITY = int(os.environ.get("WHEELCHAIR_CAMERA_JPEG_QUALITY", "75"))
RETRY_DELAY_SEC = 2.0
WAITING_FRAME_INTERVAL_SEC = 0.5


def csi_gstreamer_pipeline():
    sensor_id = int(os.environ.get("WHEELCHAIR_CAMERA_SENSOR_ID", "0"))
    flip_method = int(os.environ.get("WHEELCHAIR_CAMERA_FLIP_METHOD", "0"))
    return (
        f"nvarguscamerasrc sensor-id={sensor_id} ! "
        f"video/x-raw(memory:NVMM), width={WIDTH}, height={HEIGHT}, "
        f"format=NV12, framerate={FPS}/1 ! "
        f"nvvidconv flip-method={flip_method} ! "
        "video/x-raw, format=BGRx ! "
        "videoconvert ! "
        "video/x-raw, format=BGR ! appsink"
    )


def parse_camera_source(source):
    try:
        return int(source)
    except ValueError:
        return source


class CameraState:
    def __init__(self):
        self.lock = threading.Lock()
        self.latest_frame = None
        self.frame_count = 0
        self.last_error = None
        self.phase = "starting"
        self.last_frame_time = None
        self.running = True

    def set_frame(self, frame):
        with self.lock:
            self.latest_frame = frame
            self.frame_count += 1
            self.last_error = None
            self.phase = "streaming"
            self.last_frame_time = time.time()

    def get_frame(self):
        with self.lock:
            return self.latest_frame

    def set_error(self, message):
        print(message, flush=True)
        frame = render_status_frame(message)
        with self.lock:
            self.latest_frame = frame
            self.last_error = message
            self.phase = "error"

    def set_phase(self, phase):
        with self.lock:
            if self.phase == phase:
                return
            self.phase = phase
        print(f"Camera phase: {phase}", flush=True)

    def status(self):
        with self.lock:
            return {
                "running": self.running,
                "source": SOURCE,
                "backend": BACKEND,
                "width": WIDTH,
                "height": HEIGHT,
                "fps": FPS,
                "phase": self.phase,
                "frame_count": self.frame_count,
                "last_frame_time": self.last_frame_time,
                "last_error": self.last_error,
                "has_frame": self.latest_frame is not None,
            }


camera_state = CameraState()


def render_status_frame(message):
    image = np.zeros((HEIGHT, WIDTH, 3), dtype=np.uint8)
    lines = [
        "Wheelchair Camera",
        message,
        f"source={SOURCE}",
        "Check camera source and OpenCV/GStreamer.",
    ]
    y = 70
    for line in lines:
        cv2.putText(
            image,
            line[:80],
            (24, y),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.65,
            (255, 255, 255),
            2,
            cv2.LINE_AA,
        )
        y += 42
    ok, encoded = cv2.imencode(".jpg", image, [int(cv2.IMWRITE_JPEG_QUALITY), 85])
    if not ok:
        return b""
    return encoded.tobytes()


def open_camera():
    source = SOURCE.strip()
    if source.lower() in ("csi", "jetson_csi", "nvargus"):
        pipeline = csi_gstreamer_pipeline()
        print(f"Opening CSI camera with pipeline: {pipeline}", flush=True)
        return cv2.VideoCapture(pipeline, cv2.CAP_GSTREAMER)
    if "!" in source:
        print(f"Opening GStreamer camera source: {source}", flush=True)
        return cv2.VideoCapture(source, cv2.CAP_GSTREAMER)

    parsed_source = parse_camera_source(source)
    print(f"Opening camera source: {parsed_source} backend={BACKEND}", flush=True)
    if isinstance(parsed_source, int):
        if BACKEND == "v4l2":
            return cv2.VideoCapture(parsed_source, cv2.CAP_V4L2)
        return cv2.VideoCapture(parsed_source)
    return cv2.VideoCapture(parsed_source)


def capture_loop():
    delay = 1.0 / max(FPS, 1) 
    encode_params = [int(cv2.IMWRITE_JPEG_QUALITY), JPEG_QUALITY]
    while camera_state.running:
        camera_state.set_phase("opening")
        camera = open_camera()
        camera_state.set_phase("configuring")
        camera.set(cv2.CAP_PROP_FRAME_WIDTH, WIDTH)
        camera.set(cv2.CAP_PROP_FRAME_HEIGHT, HEIGHT)
        camera.set(cv2.CAP_PROP_FPS, FPS)

        if not camera.isOpened():
            camera_state.set_error(f"Could not open camera source: {SOURCE}")
            camera.release()
            time.sleep(RETRY_DELAY_SEC)
            continue

        camera_state.set_phase("opened")
        print(f"Camera opened: source={SOURCE}, size={WIDTH}x{HEIGHT}, fps={FPS}", flush=True)
        failed_reads = 0
        while camera_state.running:
            camera_state.set_phase("reading")
            ok, frame = camera.read()
            if not ok:
                failed_reads += 1
                if failed_reads >= max(FPS, 1):
                    camera_state.set_error(f"Camera read failed repeatedly: {SOURCE}")
                    break
                time.sleep(delay)
                continue

            failed_reads = 0
            ok, encoded = cv2.imencode(".jpg", frame, encode_params)
            if ok:
                camera_state.set_frame(encoded.tobytes())
            time.sleep(delay)

        camera.release()
        time.sleep(RETRY_DELAY_SEC)


class CameraRequestHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/health":
            data = json.dumps(camera_state.status()).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return

        if self.path == "/snapshot":
            frame = camera_state.get_frame()
            if frame is None:
                frame = render_status_frame("Waiting for camera frame")
            self.send_response(200)
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(len(frame)))
            self.end_headers()
            self.wfile.write(frame)
            return

        if self.path in ("/", "/index.html"):
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(
                b"<html><body style='margin:0;background:#000'>"
                b"<img src='/stream' style='width:100%;height:100%;object-fit:contain'>"
                b"</body></html>"
            )
            return

        if self.path != "/stream":
            self.send_error(404)
            return

        self.send_response(200)
        self.send_header("Age", "0")
        self.send_header("Cache-Control", "no-cache, private")
        self.send_header("Pragma", "no-cache")
        self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=frame")
        self.end_headers()

        while camera_state.running:
            frame = camera_state.get_frame()
            if frame is None:
                frame = render_status_frame("Waiting for camera frame")
                delay = WAITING_FRAME_INTERVAL_SEC
            else:
                delay = 1.0 / max(FPS, 1)

            try:
                self.wfile.write(b"--frame\r\n")
                self.send_header("Content-Type", "image/jpeg")
                self.send_header("Content-Length", str(len(frame)))
                self.end_headers()
                self.wfile.write(frame)
                self.wfile.write(b"\r\n")
            except (BrokenPipeError, ConnectionResetError):
                return
            time.sleep(delay)

    def log_message(self, format, *args):
        return


def main():
    capture_thread = threading.Thread(target=capture_loop, daemon=True)
    capture_thread.start()

    server = ThreadingHTTPServer((HOST, PORT), CameraRequestHandler)
    print(f"Camera MJPEG stream ready: http://{HOST}:{PORT}/stream")
    print(f"Health check: http://{HOST}:{PORT}/health", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        camera_state.running = False
        server.server_close()


if __name__ == "__main__":
    main()
