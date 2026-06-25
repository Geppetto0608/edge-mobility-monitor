package com.example.jetsonbtmonitor;

final class WheelchairTelemetryUpdate {
    private final Double speedKph;
    private final Integer batteryPercent;
    private final String mode;

    WheelchairTelemetryUpdate(Double speedKph, Integer batteryPercent, String mode) {
        this.speedKph = speedKph;
        this.batteryPercent = batteryPercent;
        this.mode = mode;
    }

    boolean hasValue() {
        return speedKph != null || batteryPercent != null || mode != null;
    }

    Double getSpeedKph() {
        return speedKph;
    }

    Integer getBatteryPercent() {
        return batteryPercent;
    }

    String getMode() {
        return mode;
    }
}
