#!/usr/bin/env bash
set -euo pipefail

# 개발 중 공용 공유기 Wi-Fi를 쓸 때는 이 스크립트를 실행하지 않습니다.
# 운용 시 Jetson이 직접 WheelchairJetson 핫스팟을 만들 때만 실행합니다.
# Android 주소 전환은 app/src/main/java/com/example/jetsonbtmonitor/AppConfig.java 에서
# 개발용 두 줄을 주석 처리하고 핫스팟용 두 줄의 주석을 해제하면 됩니다.

CONNECTION_NAME="${WHEELCHAIR_WIFI_CONNECTION:-wheelchair-jetson-ap}"
WIFI_IFACE="${WHEELCHAIR_WIFI_IFACE:-}"
WIFI_SSID="${WHEELCHAIR_WIFI_SSID:-WheelchairJetson}"
WIFI_PASSWORD="${WHEELCHAIR_WIFI_PASSWORD:-wheelchair1234}"
WIFI_IP_CIDR="${WHEELCHAIR_WIFI_IP_CIDR:-192.168.50.1/24}"
WIFI_CHANNEL="${WHEELCHAIR_WIFI_CHANNEL:-6}"

if [[ "${EUID}" -ne 0 ]]; then
    exec sudo -E bash "$0" "$@"
fi

if [[ "${#WIFI_PASSWORD}" -lt 8 || "${#WIFI_PASSWORD}" -gt 63 ]]; then
    echo "WHEELCHAIR_WIFI_PASSWORD must be 8-63 characters." >&2
    exit 1
fi

if [[ -z "${WIFI_IFACE}" ]]; then
    WIFI_IFACE="$(nmcli -t -f DEVICE,TYPE device status | awk -F: '$2 == "wifi" { print $1; exit }')"
fi

if [[ -z "${WIFI_IFACE}" ]]; then
    echo "No Wi-Fi interface found. Set WHEELCHAIR_WIFI_IFACE=wlan0 or plug in a Wi-Fi adapter." >&2
    exit 1
fi

if command -v iw >/dev/null 2>&1; then
    if ! iw list | sed -n '/Supported interface modes:/,/Band/p' | grep -q '\* AP'; then
        echo "Warning: this Wi-Fi adapter may not support AP mode." >&2
    fi
fi

nmcli radio wifi on
nmcli connection delete "${CONNECTION_NAME}" >/dev/null 2>&1 || true

nmcli connection add \
    type wifi \
    ifname "${WIFI_IFACE}" \
    con-name "${CONNECTION_NAME}" \
    autoconnect yes \
    ssid "${WIFI_SSID}"

nmcli connection modify "${CONNECTION_NAME}" \
    802-11-wireless.mode ap \
    802-11-wireless.band bg \
    802-11-wireless.channel "${WIFI_CHANNEL}" \
    ipv4.method shared \
    ipv4.addresses "${WIFI_IP_CIDR}" \
    ipv6.method ignore \
    wifi-sec.key-mgmt wpa-psk \
    wifi-sec.psk "${WIFI_PASSWORD}"

nmcli connection up "${CONNECTION_NAME}"

cat <<EOF
Jetson Wi-Fi AP is ready.

SSID:      ${WIFI_SSID}
Password:  ${WIFI_PASSWORD}
Jetson IP: ${WIFI_IP_CIDR%/*}

Android camera stream URL:
http://${WIFI_IP_CIDR%/*}:8080/stream
EOF
