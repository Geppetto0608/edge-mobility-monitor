# AGENTS.md

## Project Purpose

This repository contains an Android + Jetson monitoring system for an edge mobility platform. The Android app communicates with a Jetson AGX Orin over Wi-Fi, polls a Jetson-hosted HTTP API for ROS2-derived telemetry, displays speed, battery, mode, presets, and camera stream status, and sends mode, step-level, and stop commands back to ROS2 topics.

Treat this as an AI / robotics engineering portfolio project. Future work should make the system understandable, reproducible, and credible for recruiters, AI engineers, and robotics researchers.

## Coding Style Rules

- Preserve existing behavior unless the user explicitly asks for a refactor.
- Keep Android code in the existing Java + XML style.
- Keep Jetson scripts in plain Python standard-library style unless a dependency is already required, such as ROS2 or OpenCV.
- Keep network addresses, ports, ROS topic names, and JSON payload schemas explicit and easy to audit.
- If changing API payloads, update Android client code, Jetson bridge code, tests, and README examples together.
- Do not introduce hidden background behavior or undocumented protocol changes.

## Documentation Rules

- Write documentation for a fresh clone, not for a local machine.
- Separate Android setup, Jetson setup, ROS2 topic contract, camera streaming, and validation.
- Document required external dependencies: Android Studio, Android SDK, JDK, ROS2 Humble, Python 3, OpenCV, NetworkManager, and camera hardware where relevant.
- Mark hardware-dependent steps clearly.
- Use placeholders such as `/home/<user>/<repo>/jetson` instead of hard-coded personal systemd paths.

## README Writing Rules

- Start with a concise technical summary of what runs on Android, what runs on Jetson, and what flows over the network.
- Include an architecture section with a text diagram or image.
- Include exact validation commands:
  - `./gradlew :app:assembleDebug`
  - `./gradlew :app:testDebugUnitTest`
  - `./gradlew :app:lintDebug`
  - `source /opt/ros/humble/setup.bash && python3 jetson/ros2_wifi_bridge.py`
  - `python3 jetson/camera_mjpeg_server.py`
- Include screenshots, demo GIFs, ROS topic screenshots, API example responses, or mark them `TBD`.
- Avoid claiming real robot deployment, safety validation, or production readiness without evidence.

## Testing / Validation Rules

- For Android changes, run:
  - `./gradlew :app:testDebugUnitTest`
  - `./gradlew :app:assembleDebug`
  - `./gradlew :app:lintDebug`
- On Windows, if `JAVA_HOME` is not set but Android Studio is installed, use the Android Studio JBR.
- For Jetson Python scripts, validate syntax when Python is available:
  - `python3 -m py_compile jetson/ros2_wifi_bridge.py jetson/camera_mjpeg_server.py jetson/ros2_bt_bridge.py`
- For ROS2 behavior, validate with documented `ros2 topic pub` and `ros2 topic echo` commands.
- If hardware, ROS2, Python, or camera dependencies are unavailable, state exactly what could not be validated.

## What Codex Must Not Fabricate

- Do not invent latency, FPS, reliability, field-test outcomes, robot behavior, or safety claims.
- Do not claim the robot or wheelchair works unless evidence is committed or validation was actually run.
- Do not invent screenshots, diagrams, datasets, logs, or demo videos.
- Do not describe Bluetooth as the primary current workflow when the documented app flow uses Wi-Fi HTTP.

## Missing Results / Dataset Handling

- Use `TBD` or `To be added` for missing screenshots, demo videos, logs, diagrams, and quantitative results.
- If generated outputs are useful for docs, place curated assets under `docs/`; keep build outputs ignored.
- Treat `local.properties`, generated APKs, Gradle build outputs, keystores, and machine-specific service paths as local-only.

## Portfolio-Quality Explanation Rules

- Explain the pipeline as: Android UI -> HTTP API -> Jetson bridge -> ROS2 topics -> telemetry/commands.
- Emphasize concrete engineering choices: endpoints, polling interval, ROS2 topics, command payloads, local JSON user store, MJPEG stream, and systemd services.
- Keep limitations visible and actionable.

