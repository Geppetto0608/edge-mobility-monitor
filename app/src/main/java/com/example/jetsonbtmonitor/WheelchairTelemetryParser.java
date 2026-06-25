package com.example.jetsonbtmonitor;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

final class WheelchairTelemetryParser {
    private WheelchairTelemetryParser() {
    }

    static WheelchairTelemetryUpdate parse(String line) throws JSONException {
        JSONObject json = new JSONObject(line);
        JSONObject payload = readPayload(json);

        Double speedKph = readSpeedKph(json, payload);
        Integer batteryPercent = readBatteryPercent(json, payload);
        String mode = readMode(json, payload);

        if (json.has("topic")) {
            WheelchairTelemetryUpdate topicUpdate = parseTopicValue(
                    json.optString("topic"),
                    readTopicValue(json, payload)
            );
            speedKph = speedKph == null ? topicUpdate.getSpeedKph() : speedKph;
            batteryPercent = batteryPercent == null ? topicUpdate.getBatteryPercent() : batteryPercent;
            mode = isBlank(mode) ? topicUpdate.getMode() : mode;
        }

        return new WheelchairTelemetryUpdate(speedKph, batteryPercent, normalizeMode(mode));
    }

    private static JSONObject readPayload(JSONObject json) {
        JSONObject msg = json.optJSONObject("msg");
        if (msg != null) {
            return msg;
        }
        JSONObject value = json.optJSONObject("value");
        if (value != null) {
            return value;
        }
        return json;
    }

    private static Object readTopicValue(JSONObject json, JSONObject payload) {
        if (json.has("value")) {
            return json.opt("value");
        }
        if (payload.has("data")) {
            return payload.opt("data");
        }
        if (payload.has("percentage")) {
            return payload.opt("percentage");
        }
        return payload;
    }

    private static WheelchairTelemetryUpdate parseTopicValue(String topic, Object value) {
        String normalizedTopic = normalize(topic);
        if (normalizedTopic.contains("speed") || normalizedTopic.contains("velocity")) {
            Double speed = parseDouble(value);
            if (speed != null && isMetersPerSecondTopic(normalizedTopic)) {
                speed *= 3.6;
            }
            return new WheelchairTelemetryUpdate(speed, null, null);
        }
        if (normalizedTopic.contains("battery") || normalizedTopic.contains("soc")) {
            return new WheelchairTelemetryUpdate(null, parseBattery(value, isFractionTopic(normalizedTopic)), null);
        }
        if (normalizedTopic.contains("mode")) {
            return new WheelchairTelemetryUpdate(null, null, stringValue(value));
        }
        return new WheelchairTelemetryUpdate(null, null, null);
    }

    private static Double readSpeedKph(JSONObject json, JSONObject payload) {
        if (json.has("speed_kph")) {
            return json.optDouble("speed_kph");
        }
        if (payload.has("speed_kph")) {
            return payload.optDouble("speed_kph");
        }
        if (json.has("speed_mps")) {
            return json.optDouble("speed_mps") * 3.6;
        }
        if (payload.has("speed_mps")) {
            return payload.optDouble("speed_mps") * 3.6;
        }
        if (json.has("speed")) {
            return json.optDouble("speed");
        }
        if (payload.has("speed")) {
            return payload.optDouble("speed");
        }
        if (json.has("velocity")) {
            return json.optDouble("velocity");
        }
        if (payload.has("velocity")) {
            return payload.optDouble("velocity");
        }
        return null;
    }

    private static Integer readBatteryPercent(JSONObject json, JSONObject payload) {
        if (json.has("battery_percent")) {
            return parseBattery(json.opt("battery_percent"), false);
        }
        if (payload.has("battery_percent")) {
            return parseBattery(payload.opt("battery_percent"), false);
        }
        if (json.has("battery_fraction")) {
            return parseBattery(json.opt("battery_fraction"), true);
        }
        if (payload.has("battery_fraction")) {
            return parseBattery(payload.opt("battery_fraction"), true);
        }
        if (json.has("soc_fraction")) {
            return parseBattery(json.opt("soc_fraction"), true);
        }
        if (payload.has("soc_fraction")) {
            return parseBattery(payload.opt("soc_fraction"), true);
        }
        if (json.has("battery")) {
            return parseBattery(json.opt("battery"), false);
        }
        if (payload.has("battery")) {
            return parseBattery(payload.opt("battery"), false);
        }
        if (json.has("soc")) {
            return parseBattery(json.opt("soc"), false);
        }
        if (payload.has("soc")) {
            return parseBattery(payload.opt("soc"), false);
        }
        if (json.has("percentage")) {
            return parseBattery(json.opt("percentage"), true);
        }
        if (payload.has("percentage")) {
            return parseBattery(payload.opt("percentage"), true);
        }
        return null;
    }

    private static String readMode(JSONObject json, JSONObject payload) {
        if (json.has("mode")) {
            return json.optString("mode");
        }
        if (payload.has("mode")) {
            return payload.optString("mode");
        }
        if (json.has("driving_mode")) {
            return json.optString("driving_mode");
        }
        if (payload.has("driving_mode")) {
            return payload.optString("driving_mode");
        }
        return null;
    }

    private static Integer parseBattery(Object value, boolean fraction) {
        Double number = parseDouble(value);
        if (number == null) {
            return null;
        }
        double percent = fraction ? number * 100.0 : number;
        return (int) Math.round(percent);
    }

    private static Double parseDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String normalizeMode(String mode) {
        if (isBlank(mode)) {
            return null;
        }
        String normalizedMode = normalize(mode);
        switch (normalizedMode) {
            case "manual_mode":
                return "manual";
            case "assist_mode":
                return "assist";
            case "autonomous_mode":
            case "auto":
                return "autonomous";
            default:
                return normalizedMode;
        }
    }

    private static String stringValue(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            if (object.has("data")) {
                return object.optString("data");
            }
            if (object.has("mode")) {
                return object.optString("mode");
            }
        }
        return String.valueOf(value);
    }

    private static boolean isMetersPerSecondTopic(String topic) {
        return topic.contains("mps")
                || topic.contains("m_s")
                || topic.contains("m/s")
                || topic.contains("meter_per_second");
    }

    private static boolean isFractionTopic(String topic) {
        return topic.contains("fraction") || topic.contains("percentage");
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }
}
