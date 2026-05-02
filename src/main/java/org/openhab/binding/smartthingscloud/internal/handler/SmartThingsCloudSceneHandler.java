/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.smartthingscloud.internal.handler;

import static org.openhab.binding.smartthingscloud.SmartThingsCloudBindingConstants.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thing handler for a SmartThings scene.
 *
 * <p>
 * Sending {@code ON} to the {@code trigger} channel executes the scene via
 * {@code POST /v1/scenes/{sceneId}/execute}. The channel auto-resets to {@code OFF}
 * immediately so subsequent {@code ON} commands always fire the scene.
 *
 * <p>
 * No polling is performed — scenes are write-only.
 *
 * @author openHAB Samsung Cloud Binding - Initial contribution
 */
@NonNullByDefault
public class SmartThingsCloudSceneHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(SmartThingsCloudSceneHandler.class);

    public SmartThingsCloudSceneHandler(Thing thing) {
        super(thing);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void initialize() {
        SmartThingsCloudSceneConfiguration config = getConfigAs(SmartThingsCloudSceneConfiguration.class);
        if (config.sceneId.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Scene ID is not configured");
            return;
        }
        updateStatus(ThingStatus.ONLINE);
        // Ensure trigger channel starts at OFF
        updateState(CHANNEL_SCENE_TRIGGER, OnOffType.OFF);
    }

    @Override
    public void bridgeStatusChanged(org.openhab.core.thing.ThingStatusInfo bridgeStatusInfo) {
        if (bridgeStatusInfo.getStatus() == ThingStatus.OFFLINE) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
        } else if (bridgeStatusInfo.getStatus() == ThingStatus.ONLINE) {
            SmartThingsCloudSceneConfiguration config = getConfigAs(SmartThingsCloudSceneConfiguration.class);
            if (!config.sceneId.isBlank()) {
                updateStatus(ThingStatus.ONLINE);
            }
        }
    }

    // ── Command handling ──────────────────────────────────────────────────────

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (!CHANNEL_SCENE_TRIGGER.equals(channelUID.getId())) {
            return;
        }
        if (command != OnOffType.ON) {
            return;
        }

        SmartThingsCloudApiClient client = getApiClient();
        if (client == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE, "Bridge not available");
            return;
        }

        SmartThingsCloudSceneConfiguration config = getConfigAs(SmartThingsCloudSceneConfiguration.class);
        boolean ok = client.executeScene(config.sceneId, config.locationId);
        if (ok) {
            logger.debug("Scene {} executed successfully", config.sceneId);
        } else {
            logger.warn("Failed to execute scene {}", config.sceneId);
        }

        // Auto-reset to OFF so the next ON command always fires
        updateState(CHANNEL_SCENE_TRIGGER, OnOffType.OFF);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private @Nullable SmartThingsCloudApiClient getApiClient() {
        Bridge bridge = getBridge();
        if (bridge == null) {
            return null;
        }
        ThingHandler handler = bridge.getHandler();
        if (handler instanceof SmartThingsCloudAccountHandler accountHandler) {
            return accountHandler.getApiClient();
        }
        return null;
    }
}
