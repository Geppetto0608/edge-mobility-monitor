# Edge Mobility Monitor

Jetson AGX Orin과 Android 앱을 Wi-Fi로 연결해서 자율주행 휠체어의 상태를 확인하고 제어하는 모니터링 앱입니다.

앱은 Jetson의 ROS2 토픽을 직접 읽지 않습니다. Jetson에서 실행되는 Python 브릿지가 ROS2 토픽을 HTTP JSON API로 바꾸고, Android 앱은 그 API를 polling해서 속도, 배터리, 주행 모드, 프리셋, 카메라 영상을 표시합니다.

## 주요 기능

- Android 네이티브 앱
- Wi-Fi 기반 Jetson HTTP API 통신
- MJPEG 카메라 실시간 스트리밍
- 속도, 배터리, 주행 모드 표시
- 수동 / 어시스트 / 자율주행 모드 명령 전송
- 1-5단계 Step Level 제어
- 사용자 로그인 / 회원가입
- 사용자별 프리셋 저장
- Base / 쾌속 / 저속 프리셋 설정
- 기본 프리셋 자동 적용
- 앱 백그라운드 상태 알림 서비스
- Jetson 핫스팟 운용 준비

## 프로젝트 구조

```text
.
├── app/                         Android 앱 소스
│   └── src/main/java/com/example/jetsonbtmonitor/
│       ├── MainActivity.java     메인 대시보드
│       ├── WifiSetupActivity.java
│       ├── LoginActivity.java
│       ├── RegisterActivity.java
│       ├── SettingsActivity.java
│       ├── CameraActivity.java
│       ├── WheelchairApiClient.java
│       └── AppConfig.java        Jetson 주소 설정
├── jetson/
│   ├── ros2_wifi_bridge.py       ROS2 토픽 <-> Wi-Fi HTTP API 브릿지
│   ├── camera_mjpeg_server.py    카메라 MJPEG 스트리밍 서버
│   ├── setup_wifi_ap.sh          Jetson Wi-Fi AP 설정 스크립트
│   └── README.md                 Jetson 실행 상세 문서
├── gradle/
├── build.gradle.kts
└── settings.gradle.kts
```


## Android 앱 실행 방법

### 1. 설치 필요

- Android Studio
- JDK 17 이상 권장
- Android SDK
- USB 디버깅이 켜진 Android 휴대폰

Android Studio를 설치한 뒤 이 저장소를 clone합니다.

```bash
git clone https://github.com/Geppetto0608/edge-mobility-monitor.git
cd edge-mobility-monitor
```

### 2. Android Studio에서 열기

Android Studio에서 `File > Open`을 누르고 clone한 `edge-mobility-monitor` 폴더를 선택합니다.

처음 열면 Gradle Sync가 자동으로 실행됩니다. `local.properties`는 Android Studio가 각 PC의 SDK 경로에 맞게 자동 생성합니다.

### 3. Jetson 주소 확인

앱이 접속하는 Jetson 주소는 아래 파일 한 곳에서 관리합니다.

```text
app/src/main/java/com/example/jetsonbtmonitor/AppConfig.java
```

현재 개발 환경은 Jetson과 휴대폰이 같은 공유기 Wi-Fi에 붙어 있는 구조입니다.

```java
static final String JETSON_API_BASE_URL = "http://192.168.0.243:8081";
static final String CAMERA_STREAM_URL = "http://192.168.0.243:8080/stream";
```

나중에 Jetson이 직접 Wi-Fi 핫스팟을 만들면 위 두 줄을 주석 처리하고, 아래 핫스팟 주소 두 줄의 주석을 해제합니다.

```java
// static final String JETSON_API_BASE_URL = "http://192.168.50.1:8081";
// static final String CAMERA_STREAM_URL = "http://192.168.50.1:8080/stream";
```

### 4. 빌드 확인

터미널에서 직접 확인할 수도 있습니다.

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

### 5. 휴대폰에 설치

Android Studio에서 Run 버튼을 누르면 됩니다.

터미널로 설치하려면:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Jetson 실행 방법

Jetson 쪽 상세 문서는 [jetson/README.md](jetson/README.md)에 정리되어 있습니다.

기본 실행 순서는 아래와 같습니다.

```bash
cd jetson
source /opt/ros/humble/setup.bash
python3 ros2_wifi_bridge.py
```

다른 터미널에서 카메라 서버를 실행합니다.

```bash
cd jetson
python3 camera_mjpeg_server.py
```

API 상태 확인:

```bash
curl http://127.0.0.1:8081/api/health
curl http://127.0.0.1:8081/api/telemetry
```

카메라 상태 확인:

```bash
curl http://127.0.0.1:8080/health
curl -o snapshot.jpg http://127.0.0.1:8080/snapshot
```

## ROS2 토픽 규격

Jetson Wi-Fi 브릿지가 구독하는 토픽입니다.

```text
/wheelchair/speed_mps          std_msgs/msg/Float32
/wheelchair/speed_kph          std_msgs/msg/Float32
/cmd_vel                       geometry_msgs/msg/Twist
/wheelchair/battery_percent    std_msgs/msg/Int32
/battery/soc                   sensor_msgs/msg/BatteryState
/wheelchair/mode               std_msgs/msg/String
```

앱에서 버튼을 누르면 Jetson 브릿지가 아래 토픽으로 명령을 발행합니다.

```text
/wheelchair/mode_command       std_msgs/msg/String
/wheelchair/step_command       std_msgs/msg/Int32
/wheelchair/stop_command       std_msgs/msg/String
```

기본 주행 모드 값은 아래 문자열을 사용합니다.

```text
manual
assist
autonomous
```

Step Level은 1-5 정수입니다.

## 빠른 ROS2 테스트

Jetson에서 앱 화면 테스트용 토픽을 발행할 수 있습니다.

```bash
ros2 topic pub -r 5 /wheelchair/speed_mps std_msgs/msg/Float32 "{data: 1.2}"
ros2 topic pub -r 5 /cmd_vel geometry_msgs/msg/Twist "{linear: {x: 1.2}}"
ros2 topic pub -r 1 /wheelchair/battery_percent std_msgs/msg/Int32 "{data: 87}"
ros2 topic pub -r 1 /wheelchair/mode std_msgs/msg/String "{data: assist}"
```

앱에서 명령을 눌렀을 때 Jetson에서 확인:

```bash
ros2 topic echo /wheelchair/mode_command
ros2 topic echo /wheelchair/step_command
ros2 topic echo /wheelchair/stop_command
```

## 사용자 데이터 저장

회원가입, 로그인 기록, 사용 기록, 프리셋 데이터는 Jetson Wi-Fi 브릿지가 Jetson 로컬 디렉터리에 JSON 파일로 저장합니다.

기본 저장 위치:

```text
~/.wheelchair_monitor/
```

환경 변수로 변경할 수 있습니다.

```bash
WHEELCHAIR_DATA_DIR=/path/to/data python3 ros2_wifi_bridge.py
```

## Wi-Fi 운용 방식

현재 개발 환경:

```text
휴대폰 ---- 같은 공유기 Wi-Fi ---- Jetson
Jetson IP: 192.168.0.243
```

실제 운용 예정:

```text
휴대폰 ---- Jetson이 만든 Wi-Fi AP ---- Jetson
Jetson AP IP: 192.168.50.1
```

Jetson 핫스팟 설정:

```bash
cd jetson
chmod +x setup_wifi_ap.sh
./setup_wifi_ap.sh
```

기본값:

```text
SSID: WheelchairJetson
Password: wheelchair1234
Jetson IP: 192.168.50.1
```

## 자주 생기는 문제

### 앱에서 Jetson에 연결되지 않음

1. 휴대폰과 Jetson이 같은 네트워크에 있는지 확인합니다.
2. `AppConfig.java`의 IP가 Jetson 실제 IP와 같은지 확인합니다.
3. Jetson에서 아래 명령이 되는지 확인합니다.

```bash
curl http://127.0.0.1:8081/api/health
```

4. 휴대폰 브라우저에서 아래 주소가 열리는지 확인합니다.

```text
http://192.168.0.243:8081/api/health
```

### 카메라가 보이지 않음

Jetson에서 먼저 확인합니다.

```bash
curl http://127.0.0.1:8080/health
curl -o snapshot.jpg http://127.0.0.1:8080/snapshot
```

USB 카메라 번호가 다르면:

```bash
WHEELCHAIR_CAMERA_SOURCE=1 python3 jetson/camera_mjpeg_server.py
```

CSI 카메라를 시도하려면:

```bash
WHEELCHAIR_CAMERA_SOURCE=csi python3 jetson/camera_mjpeg_server.py
```

### Current Mode가 토픽과 다르게 보임

앱은 `/wheelchair/mode` 토픽이 들어오면 그 값을 우선 표시합니다.
토픽이 아직 없으면 앱에서 마지막으로 누른 모드를 유지해서 보여줍니다.

프리셋 이름인 `Base 모드`, `쾌속 모드`, `저속 모드`는 카드 강조로 표시되고, Current Mode에는 실제 주행 모드인 `수동 모드`, `어시스트 모드`, `자율주행 모드`가 표시됩니다.

## Git 관리

처음 clone:

```bash
git clone https://github.com/Geppetto0608/edge-mobility-monitor.git
```

변경사항 저장:

```bash
git status
git add .
git commit -m "변경 내용 요약"
git push
```

다른 사람이 올린 변경사항 받기:

```bash
git pull
```
