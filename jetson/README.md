# Jetson ROS2 Wi-Fi Bridge

Android 앱은 ROS 토픽을 직접 읽지 않고 Wi-Fi HTTP API로 JSON을 주고받습니다.
Jetson에서는 `ros2_wifi_bridge.py`를 실행해서 ROS2 토픽을 앱용 API로 변환합니다.
카메라 영상은 `camera_mjpeg_server.py`가 HTTP MJPEG 스트림으로 제공합니다.

## Jetson 준비

```bash
sudo apt update
sudo apt install -y python3-opencv
```

## 개발/운용 주소 전환

앱의 Jetson 주소는 Android 코드의 `AppConfig.java` 한 곳에서 바꿉니다.

```text
app/src/main/java/com/example/jetsonbtmonitor/AppConfig.java
```

개발 중 공용 공유기 Wi-Fi를 쓸 때는 `192.168.0.243` 줄을 사용합니다.
Jetson 핫스팟 운용으로 바꿀 때는 개발용 두 줄을 주석 처리하고,
`192.168.50.1` 핫스팟용 두 줄의 주석을 해제합니다.

## Wi-Fi API 브릿지 실행

ROS2 환경을 먼저 source 한 뒤 실행합니다.

```bash
source /opt/ros/humble/setup.bash
python3 ros2_wifi_bridge.py
```

기본 API 주소는 `http://Jetson_IP:8081` 입니다.

```bash
curl http://127.0.0.1:8081/api/health
curl http://127.0.0.1:8081/api/telemetry
```

## 카메라 스트리밍

카메라 영상은 Bluetooth가 아니라 Wi-Fi HTTP MJPEG로 봅니다.
운용 시에는 Jetson이 인터넷 없는 전용 Wi-Fi AP를 만들고, 휴대폰이 그 Wi-Fi에 붙는 구조를 권장합니다.

Jetson Wi-Fi AP를 만듭니다.

```bash
chmod +x setup_wifi_ap.sh
./setup_wifi_ap.sh
```

기본값은 아래와 같습니다.

```text
SSID: WheelchairJetson
Password: wheelchair1234
Jetson IP: 192.168.50.1
```

휴대폰 Wi-Fi 설정에서 `WheelchairJetson`에 연결합니다.
Android가 "인터넷 없음"이라고 경고하면 이 네트워크를 계속 사용하도록 선택합니다.

Jetson에서 OpenCV가 없다면 먼저 설치합니다.

```bash
sudo apt install -y python3-opencv
```

USB 카메라나 기본 카메라 장치가 `/dev/video0`이면:

```bash
python3 camera_mjpeg_server.py
```

Android 앱에서 `카메라 보기`를 누르면 기본으로 아래 주소를 엽니다.

```text
http://192.168.50.1:8080/stream
```

카메라 장치 번호나 포트를 바꾸려면:

```bash
WHEELCHAIR_CAMERA_SOURCE=1 WHEELCHAIR_CAMERA_PORT=8082 python3 camera_mjpeg_server.py
```

CSI 카메라처럼 GStreamer pipeline이 필요하면 `WHEELCHAIR_CAMERA_SOURCE`에 pipeline 문자열을 넣어 실행합니다.
Jetson CSI 카메라를 바로 시도하려면:

```bash
WHEELCHAIR_CAMERA_SOURCE=csi python3 camera_mjpeg_server.py
```

카메라가 안 보이면 Jetson에서 먼저 확인합니다.

```bash
curl http://127.0.0.1:8080/health
curl -o snapshot.jpg http://127.0.0.1:8080/snapshot
```

폰 브라우저에서도 아래 주소가 열리는지 확인합니다.

```text
http://192.168.50.1:8080/stream
```

## 부팅 후 자동 실행

Jetson에서 Wi-Fi API 브릿지와 카메라 서버를 항상 켜두려면 systemd 서비스로 등록합니다.

서비스 파일의 `User`와 `WorkingDirectory`가 실제 Jetson 계정/경로와 맞는지 먼저 확인합니다.
현재 예시는 아래 경로 기준입니다.

```text
User=unicon
WorkingDirectory=/home/unicon/JetsonBtMonitor/jetson
```

다른 경로에 clone했다면 `wheelchair-wifi-bridge.service`, `wheelchair-camera-stream.service` 안의 값을 수정합니다.

서비스 등록:

```bash
sudo cp wheelchair-wifi-bridge.service /etc/systemd/system/
sudo cp wheelchair-camera-stream.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now wheelchair-wifi-bridge.service
sudo systemctl enable --now wheelchair-camera-stream.service
```

상태와 로그 확인:

```bash
systemctl status wheelchair-wifi-bridge.service
systemctl status wheelchair-camera-stream.service
journalctl -u wheelchair-wifi-bridge.service -f
journalctl -u wheelchair-camera-stream.service -f
```

서비스를 끄려면:

```bash
sudo systemctl disable --now wheelchair-wifi-bridge.service
sudo systemctl disable --now wheelchair-camera-stream.service
```

## 브릿지가 구독하는 토픽

```text
/wheelchair/speed_mps          std_msgs/msg/Float32
/wheelchair/speed_kph          std_msgs/msg/Float32
/cmd_vel                       geometry_msgs/msg/Twist
/wheelchair/battery_percent    std_msgs/msg/Int32
/battery/soc                   sensor_msgs/msg/BatteryState
/wheelchair/mode               std_msgs/msg/String
```

`/cmd_vel`처럼 `geometry_msgs/msg/Twist` 타입인 속도 토픽은 `linear.x` 값을 m/s로 읽어서 앱에 보냅니다.
실제 Twist 토픽 이름이 다르면 실행할 때 바꿀 수 있습니다.

```bash
WHEELCHAIR_TWIST_SPEED_TOPIC=/실제/twist_토픽 python3 ros2_wifi_bridge.py
```

## 앱에서 모드 버튼을 누르면 발행되는 토픽

```text
/wheelchair/mode_command       std_msgs/msg/String
/wheelchair/step_command       std_msgs/msg/Int32
/wheelchair/stop_command       std_msgs/msg/String
```

## 빠른 테스트 발행

터미널을 여러 개 열어서 아래처럼 테스트할 수 있습니다.

```bash
ros2 topic pub -r 5 /wheelchair/speed_mps std_msgs/msg/Float32 "{data: 1.2}"
ros2 topic pub -r 5 /cmd_vel geometry_msgs/msg/Twist "{linear: {x: 1.2}}"
ros2 topic pub -r 1 /wheelchair/battery_percent std_msgs/msg/Int32 "{data: 87}"
ros2 topic pub -r 1 /wheelchair/mode std_msgs/msg/String "{data: assist}"
```

앱에서 모드 버튼을 눌렀을 때 Jetson에서 확인:

```bash
ros2 topic echo /wheelchair/mode_command
ros2 topic echo /wheelchair/step_command
ros2 topic echo /wheelchair/stop_command
```
