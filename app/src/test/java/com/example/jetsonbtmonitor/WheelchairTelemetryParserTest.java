package com.example.jetsonbtmonitor;

import org.json.JSONException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WheelchairTelemetryParserTest {
    @Test
    public void parsesFlatTelemetryPayload() throws JSONException {
        WheelchairTelemetryUpdate update = WheelchairTelemetryParser.parse(
                "{\"speed_kph\":3.2,\"battery_percent\":87,\"mode\":\"assist\"}"
        );

        assertEquals(3.2, update.getSpeedKph(), 0.001);
        assertEquals(Integer.valueOf(87), update.getBatteryPercent());
        assertEquals("assist", update.getMode());
        assertTrue(update.hasValue());
    }

    @Test
    public void convertsMetersPerSecondSpeed() throws JSONException {
        WheelchairTelemetryUpdate update = WheelchairTelemetryParser.parse(
                "{\"speed_mps\":1.5}"
        );

        assertEquals(5.4, update.getSpeedKph(), 0.001);
        assertNull(update.getBatteryPercent());
        assertNull(update.getMode());
    }

    @Test
    public void parsesTopicValueMessages() throws JSONException {
        WheelchairTelemetryUpdate speedUpdate = WheelchairTelemetryParser.parse(
                "{\"topic\":\"/wheelchair/speed_mps\",\"value\":2.0}"
        );
        WheelchairTelemetryUpdate batteryUpdate = WheelchairTelemetryParser.parse(
                "{\"topic\":\"/wheelchair/battery\",\"value\":\"73\"}"
        );
        WheelchairTelemetryUpdate modeUpdate = WheelchairTelemetryParser.parse(
                "{\"topic\":\"/wheelchair/mode\",\"value\":\"autonomous_mode\"}"
        );

        assertEquals(7.2, speedUpdate.getSpeedKph(), 0.001);
        assertEquals(Integer.valueOf(73), batteryUpdate.getBatteryPercent());
        assertEquals("autonomous", modeUpdate.getMode());
    }

    @Test
    public void parsesRosBridgeStyleMessages() throws JSONException {
        WheelchairTelemetryUpdate batteryUpdate = WheelchairTelemetryParser.parse(
                "{\"op\":\"publish\",\"topic\":\"/battery/soc\",\"msg\":{\"percentage\":0.64}}"
        );
        WheelchairTelemetryUpdate modeUpdate = WheelchairTelemetryParser.parse(
                "{\"op\":\"publish\",\"topic\":\"/drive/mode\",\"msg\":{\"data\":\"manual_mode\"}}"
        );

        assertEquals(Integer.valueOf(64), batteryUpdate.getBatteryPercent());
        assertEquals("manual", modeUpdate.getMode());
    }

    @Test
    public void reportsNoValueForUnknownTopic() throws JSONException {
        WheelchairTelemetryUpdate update = WheelchairTelemetryParser.parse(
                "{\"topic\":\"/wheelchair/temperature\",\"value\":32}"
        );

        assertFalse(update.hasValue());
    }
}
