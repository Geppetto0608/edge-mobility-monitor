package com.example.jetsonbtmonitor;

final class WheelchairTelemetry {
    private final Double speedKph;
    private final Integer batteryPercent;
    private final String mode;

    private WheelchairTelemetry(Double speedKph, Integer batteryPercent, String mode) {
        this.speedKph = speedKph;
        this.batteryPercent = batteryPercent;
        this.mode = mode;
    }

    static WheelchairTelemetry empty() {
        return new WheelchairTelemetry(null, null, null);
    }

    WheelchairTelemetry merge(WheelchairTelemetryUpdate update) {
        return new WheelchairTelemetry(
                update.getSpeedKph() == null ? speedKph : update.getSpeedKph(),
                update.getBatteryPercent() == null ? batteryPercent : update.getBatteryPercent(),
                update.getMode() == null ? mode : update.getMode()
        );
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
