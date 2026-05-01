/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.smartthingscloud.internal.handler;

import static org.openhab.binding.smartthingscloud.SmartThingsCloudBindingConstants.*;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Thing handler for a SmartThings light sensor (e.g. the embedded sensor in a Samsung TV panel).
 *
 * <p>
 * Capability → channel mapping:
 * <ul>
 * <li>{@code illuminanceMeasurement.illuminance} → {@code illuminance} (lux, read-only)</li>
 * <li>{@code relativeBrightness.brightnessIntensity} → {@code brightnessLevel} (level 1–5, read-only)</li>
 * </ul>
 *
 * @author openHAB Samsung Cloud Binding - Initial contribution
 */
@NonNullByDefault
public class SmartThingsCloudLightSensorHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(SmartThingsCloudLightSensorHandler.class);
    private final Gson gson = new GsonBuilder().create();

    private @Nullable ScheduledFuture<?> pollFuture;

    public SmartThingsCloudLightSensorHandler(Thing thing) {
        super(thing);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void initialize() {
        SmartThingsCloudTelevisionConfiguration config = getConfigAs(SmartThingsCloudTelevisionConfiguration.class);
        if (config.deviceId.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Device ID is not configured");
            return;
        }

        updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE, "Waiting for first poll");
        schedulePoll(config.pollingIntervalSeconds);
    }

    @Override
    public void dispose() {
        cancelPoll();
        super.dispose();
    }

    @Override
    public void bridgeStatusChanged(org.openhab.core.thing.ThingStatusInfo bridgeStatusInfo) {
        if (bridgeStatusInfo.getStatus() == ThingStatus.OFFLINE) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
            cancelPoll();
        } else if (bridgeStatusInfo.getStatus() == ThingStatus.ONLINE) {
            SmartThingsCloudTelevisionConfiguration config = getConfigAs(SmartThingsCloudTelevisionConfiguration.class);
            schedulePoll(config.pollingIntervalSeconds);
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            poll();
        }
    }

    // ── Polling ────────────────────────────────────────────────────────────────

    private void schedulePoll(int intervalSeconds) {
        cancelPoll();
        pollFuture = scheduler.scheduleWithFixedDelay(this::poll, 0, intervalSeconds, TimeUnit.SECONDS);
    }

    private void cancelPoll() {
        ScheduledFuture<?> f = pollFuture;
        if (f != null) {
            f.cancel(true);
            pollFuture = null;
        }
    }

    private void poll() {
        SmartThingsCloudApiClient client = getApiClient();
        if (client == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE, "Bridge not available");
            return;
        }

        String deviceId = getConfigAs(SmartThingsCloudTelevisionConfiguration.class).deviceId;
        String json = client.getDeviceComponentStatus(deviceId);

        if (json == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "No response from SmartThings API");
            return;
        }

        try {
            parseAndUpdate(json);
            updateStatus(ThingStatus.ONLINE);
        } catch (Exception e) {
            logger.warn("Failed to parse SmartThings status for device {}: {}", deviceId, e.getMessage(), e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Parse error: " + e.getMessage());
        }
    }

    // ── JSON parsing ──────────────────────────────────────────────────────────

    private void parseAndUpdate(String json) {
        JsonObject root = gson.fromJson(json, JsonObject.class);
        if (root == null) {
            logger.warn("Null root in SmartThings light sensor status response");
            return;
        }

        // ── illuminanceMeasurement ────────────────────────────────────────────
        JsonElement illumCap = root.get("illuminanceMeasurement");
        if (illumCap != null && illumCap.isJsonObject()) {
            JsonObject im = illumCap.getAsJsonObject();
            JsonElement illumAttr = im.get("illuminance");
            if (illumAttr != null && illumAttr.isJsonObject()) {
                JsonElement val = illumAttr.getAsJsonObject().get("value");
                if (val != null && !val.isJsonNull()) {
                    try {
                        updateState(CHANNEL_ILLUMINANCE, new DecimalType(val.getAsDouble()));
                    } catch (Exception e) {
                        logger.debug("Could not parse illuminance: {}", val);
                    }
                }
            }
        }

        // ── relativeBrightness ────────────────────────────────────────────────
        JsonElement brightCap = root.get("relativeBrightness");
        if (brightCap != null && brightCap.isJsonObject()) {
            JsonObject rb = brightCap.getAsJsonObject();
            JsonElement brightAttr = rb.get("brightnessIntensity");
            if (brightAttr != null && brightAttr.isJsonObject()) {
                JsonElement val = brightAttr.getAsJsonObject().get("value");
                if (val != null && !val.isJsonNull()) {
                    try {
                        updateState(CHANNEL_BRIGHTNESS_LEVEL, new DecimalType(val.getAsInt()));
                    } catch (Exception e) {
                        logger.debug("Could not parse brightnessIntensity: {}", val);
                    }
                }
            }
        }
    }

    private @Nullable SmartThingsCloudApiClient getApiClient() {
        Bridge bridge = getBridge();
        if (bridge == null)
            return null;
        ThingHandler handler = bridge.getHandler();
        if (handler instanceof SmartThingsCloudAccountHandler accountHandler) {
            return accountHandler.getApiClient();
        }
        return null;
    }
}
