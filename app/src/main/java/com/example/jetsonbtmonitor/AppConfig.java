package com.example.jetsonbtmonitor;

final class AppConfig {
    private AppConfig() {
    }

    static final String WHEELCHAIR_WIFI_SSID = "WheelchairJetson";
    static final String SUPPORT_PHONE = "010-7282-0961";

    // 개발 환경: Jetson과 휴대폰이 같은 공유기 Wi-Fi에 붙어 있을 때 사용.
    // 핫스팟 운용으로 바꿀 때는 이 두 줄을 주석 처리하고, 아래 핫스팟 주소 두 줄의 주석을 해제하면 됨.
    static final String JETSON_API_BASE_URL = "http://192.168.0.243:8081";
    static final String CAMERA_STREAM_URL = "http://192.168.0.243:8080/stream";

    // 운용 환경: Jetson이 WheelchairJetson Wi-Fi AP를 만들 때 사용.
    // static final String JETSON_API_BASE_URL = "http://192.168.50.1:8081";
    // static final String CAMERA_STREAM_URL = "http://192.168.50.1:8080/stream";
}
