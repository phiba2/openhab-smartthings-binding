/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.smartthingscloud.internal.handler;

import static org.openhab.binding.smartthingscloud.SmartThingsCloudBindingConstants.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
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
 * Thing handler for a Samsung SmartThings-connected television.
 *
 * <p>
 * Polls the SmartThings REST API at a configurable interval and maps Samsung TV capabilities
 * to openHAB channels. Supports commands for power, volume, mute, input source, and channel.
 *
 * <p>
 * Capability → channel mapping:
 * <ul>
 * <li>{@code switch.switch} → {@code power} (read/write)</li>
 * <li>{@code audioVolume.volume} → {@code tvVolume} (read/write)</li>
 * <li>{@code audioMute.mute} → {@code tvMute} (read/write)</li>
 * <li>{@code mediaInputSource.inputSource} → {@code inputSource} (read/write)</li>
 * <li>{@code tvChannel.tvChannel} → {@code tvChannel} (read/write)</li>
 * <li>{@code tvChannel.tvChannelName} → {@code tvChannelName} (read)</li>
 * <li>{@code custom.picturemode.pictureMode} → {@code pictureMode} (read/write)</li>
 * <li>{@code custom.soundmode.soundMode} → {@code soundMode} (read/write)</li>
 * <li>{@code mediaPlayback.playbackStatus} → {@code playbackStatus} (read)</li>
 * <li>{@code powerConsumptionReport.powerConsumption.power} → {@code watt} (read)</li>
 * <li>{@code powerConsumptionReport.powerConsumption.energy} → {@code kwh} (read)</li>
 * <li>{@code samsungvd.firmwareVersion.firmwareVersion} → {@code firmwareVersion} (read)</li>
 * <li>{@code samsungvd.thingStatus.status} → {@code onlineStatus} (read)</li>
 * </ul>
 *
 * @author openHAB Samsung Cloud Binding - Initial contribution
 */
@NonNullByDefault
public class SmartThingsCloudTelevisionHandler extends BaseThingHandler {

    private static final String CMD_ON = "{\"commands\":[{\"component\":\"main\",\"capability\":\"switch\",\"command\":\"on\"}]}";
    private static final String CMD_OFF = "{\"commands\":[{\"component\":\"main\",\"capability\":\"switch\",\"command\":\"off\"}]}";
    private static final String CMD_MUTE = "{\"commands\":[{\"component\":\"main\",\"capability\":\"audioMute\",\"command\":\"mute\"}]}";
    private static final String CMD_UNMUTE = "{\"commands\":[{\"component\":\"main\",\"capability\":\"audioMute\",\"command\":\"unmute\"}]}";
    private static final String CMD_VOLUME_FMT = "{\"commands\":[{\"component\":\"main\",\"capability\":\"audioVolume\",\"command\":\"setVolume\",\"arguments\":[%d]}]}";
    private static final String CMD_INPUT_FMT = "{\"commands\":[{\"component\":\"main\",\"capability\":\"samsungvd.mediaInputSource\",\"command\":\"setInputSource\",\"arguments\":[\"%s\"]}]}";
    private static final String CMD_CHANNEL_UP = "{\"commands\":[{\"component\":\"main\",\"capability\":\"tvChannel\",\"command\":\"channelUp\"}]}";
    private static final String CMD_CHANNEL_DOWN = "{\"commands\":[{\"component\":\"main\",\"capability\":\"tvChannel\",\"command\":\"channelDown\"}]}";
    private static final String CMD_CHANNEL_FMT = "{\"commands\":[{\"component\":\"main\",\"capability\":\"tvChannel\",\"command\":\"setTvChannel\",\"arguments\":[\"%s\"]}]}";
    private static final String CMD_PICTURE_FMT = "{\"commands\":[{\"component\":\"main\",\"capability\":\"custom.picturemode\",\"command\":\"setPictureMode\",\"arguments\":[\"%s\"]}]}";
    private static final String CMD_SOUND_FMT = "{\"commands\":[{\"component\":\"main\",\"capability\":\"custom.soundmode\",\"command\":\"setSoundMode\",\"arguments\":[\"%s\"]}]}";

    private final Logger logger = LoggerFactory.getLogger(SmartThingsCloudTelevisionHandler.class);
    private final Gson gson = new GsonBuilder().create();

    private @Nullable ScheduledFuture<?> pollFuture;
    /** Channels suppressed from poll updates after a command (ms epoch until suppression expires). */
    /** Last seen SmartThings timestamps for stale-data detection (volume/mute never update on Samsung TVs). */
    private final Map<String, String> lastTimestamp = new ConcurrentHashMap<>();

    public SmartThingsCloudTelevisionHandler(Thing thing) {
        super(thing);
    }

    /**
     * Returns true if the SmartThings timestamp for this attribute is unchanged since last poll.
     * Samsung TVs never push volume/mute back to cloud — timestamp stays frozen.
     */
    private boolean isStale(JsonObject capability, String attribute, String trackingKey) {
        JsonElement attr = capability.get(attribute);
        if (attr == null || !attr.isJsonObject()) return true;
        String ts = attr.getAsJsonObject().has("timestamp")
                ? attr.getAsJsonObject().get("timestamp").getAsString() : "";
        String prev = lastTimestamp.get(trackingKey);
        if (ts.equals(prev)) return true; // unchanged — stale
        lastTimestamp.put(trackingKey, ts);
        return false;
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

    // ── Commands ──────────────────────────────────────────────────────────────

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            poll();
            return;
        }

        SmartThingsCloudApiClient client = getApiClient();
        if (client == null) {
            logger.warn("Cannot send command — bridge not ready");
            return;
        }

        String deviceId = getConfigAs(SmartThingsCloudTelevisionConfiguration.class).deviceId;
        String channelId = channelUID.getIdWithoutGroup();

        if (CHANNEL_POWER.equals(channelId)) {
            client.sendCommand(deviceId, OnOffType.ON.equals(command) ? CMD_ON : CMD_OFF);
            scheduleRefreshPoll();

        } else if (CHANNEL_TV_VOLUME.equals(channelId)) {
            try {
                int vol = (int) Math.round(Double.parseDouble(command.toString()));
                client.sendCommand(deviceId, String.format(CMD_VOLUME_FMT, vol));
                updateState(channelId, new DecimalType(vol)); // optimistic
            } catch (NumberFormatException e) {
                logger.warn("Invalid volume command '{}' — expected integer", command);
            }

        } else if (CHANNEL_TV_MUTE.equals(channelId)) {
            client.sendCommand(deviceId, OnOffType.ON.equals(command) ? CMD_MUTE : CMD_UNMUTE);
            updateState(channelId, (OnOffType) command); // optimistic

        } else if (CHANNEL_TV_INPUT.equals(channelId)) {
            client.sendCommand(deviceId, String.format(CMD_INPUT_FMT, command.toString()));
            updateState(channelId, new StringType(command.toString())); // optimistic
            scheduleRefreshPoll();

        } else if (CHANNEL_TV_CHANNEL.equals(channelId)) {
            client.sendCommand(deviceId, String.format(CMD_CHANNEL_FMT, command.toString()));
            updateState(channelId, new StringType(command.toString())); // optimistic
            scheduleRefreshPoll();

        } else if (CHANNEL_TV_CHANNEL_UP.equals(channelId)) {
            if (OnOffType.ON.equals(command)) {
                client.sendCommand(deviceId, CMD_CHANNEL_UP);
                updateState(channelId, OnOffType.OFF);
                scheduleRefreshPoll();
            }

        } else if (CHANNEL_TV_CHANNEL_DOWN.equals(channelId)) {
            if (OnOffType.ON.equals(command)) {
                client.sendCommand(deviceId, CMD_CHANNEL_DOWN);
                updateState(channelId, OnOffType.OFF);
                scheduleRefreshPoll();
            }

        } else if (CHANNEL_TV_PICTURE_MODE.equals(channelId)) {
            client.sendCommand(deviceId, String.format(CMD_PICTURE_FMT, command.toString()));
            updateState(channelId, new StringType(command.toString())); // optimistic
            scheduleRefreshPoll();

        } else if (CHANNEL_TV_SOUND_MODE.equals(channelId)) {
            client.sendCommand(deviceId, String.format(CMD_SOUND_FMT, command.toString()));
            updateState(channelId, new StringType(command.toString())); // optimistic
            scheduleRefreshPoll();

        } else {
            logger.debug("No command handler for channel {}", channelId);
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

    /** Schedule a single extra poll after a command to pick up new state quickly. */
    private void scheduleRefreshPoll() {
        scheduler.schedule(this::poll, 5, TimeUnit.SECONDS);
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
            logger.warn("Null root in SmartThings TV status response");
            return;
        }

        // ── power — switch ────────────────────────────────────────────────────
        JsonObject sw = getCapability(root, "switch");
        if (sw != null) {
            String val = strVal(sw, "switch");
            if (val != null) {
                updateState(CHANNEL_POWER, OnOffType.from("on".equalsIgnoreCase(val)));
            }
        }

        // ── volume — audioVolume ──────────────────────────────────────────────
        // Samsung TVs never push volume back to SmartThings cloud — timestamp stays frozen.
        // Only update if timestamp changed (i.e. TV actually reported a new value).
        JsonObject audioVol = getCapability(root, "audioVolume");
        if (audioVol != null && !isStale(audioVol, "volume", "vol_ts")) {
            JsonElement volElem = attrValue(audioVol, "volume");
            if (volElem != null && !volElem.isJsonNull()) {
                try {
                    updateState(CHANNEL_TV_VOLUME, new DecimalType(volElem.getAsInt()));
                } catch (Exception e) {
                    logger.debug("Could not parse audioVolume: {}", volElem);
                }
            }
        }

        // ── mute — audioMute ──────────────────────────────────────────────────
        // Same: only update if timestamp changed.
        JsonObject audioMute = getCapability(root, "audioMute");
        if (audioMute != null && !isStale(audioMute, "mute", "mute_ts")) {
            String val = strVal(audioMute, "mute");
            if (val != null) {
                updateState(CHANNEL_TV_MUTE, OnOffType.from("muted".equalsIgnoreCase(val)));
            }
        }

        // ── input source — samsungvd.mediaInputSource (standard mediaInputSource is always empty on Samsung OCF TVs)
        JsonObject samsungInput = getCapability(root, "samsungvd.mediaInputSource");
        if (samsungInput != null) {
            String inputVal = strVal(samsungInput, "inputSource");
            if (inputVal != null) {
                updateState(CHANNEL_TV_INPUT, new StringType(inputVal));
            }
            // ── supported inputs — build comma-separated list from supportedInputSourcesMap
            JsonElement mapElem = attrValue(samsungInput, "supportedInputSourcesMap");
            if (mapElem != null && mapElem.isJsonArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonElement entry : mapElem.getAsJsonArray()) {
                    if (entry.isJsonObject()) {
                        JsonObject obj = entry.getAsJsonObject();
                        String id = obj.has("id") ? obj.get("id").getAsString() : null;
                        String name = obj.has("name") ? obj.get("name").getAsString() : null;
                        if (id != null && name != null) {
                            if (sb.length() > 0) sb.append(", ");
                            sb.append(id).append("=").append(name);
                        }
                    }
                }
                if (sb.length() > 0) {
                    updateState(CHANNEL_TV_SUPPORTED_INPUTS, new StringType(sb.toString()));
                }
            }
        }

        // ── TV channel — tvChannel ────────────────────────────────────────────
        JsonObject tvCh = getCapability(root, "tvChannel");
        if (tvCh != null) {
            String ch = strVal(tvCh, "tvChannel");
            if (ch != null) {
                updateState(CHANNEL_TV_CHANNEL, new StringType(ch));
            }
            String chName = strVal(tvCh, "tvChannelName");
            if (chName != null) {
                updateState(CHANNEL_TV_CHANNEL_NAME, new StringType(chName));
            }
        }

        // ── picture mode — custom.picturemode ─────────────────────────────────
        JsonObject picMode = getCapability(root, "custom.picturemode");
        if (picMode != null) {
            String val = strVal(picMode, "pictureMode");
            if (val != null) {
                updateState(CHANNEL_TV_PICTURE_MODE, new StringType(val));
            }            // supportedPictureModes as comma-separated list
            JsonElement modesElem = attrValue(picMode, "supportedPictureModes");
            if (modesElem != null && modesElem.isJsonArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonElement m : modesElem.getAsJsonArray()) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(m.getAsString());
                }
                if (sb.length() > 0) updateState(CHANNEL_TV_SUPPORTED_PICTURE_MODES, new StringType(sb.toString()));
            }        }

        // ── sound mode — custom.soundmode ─────────────────────────────────────
        JsonObject sndMode = getCapability(root, "custom.soundmode");
        if (sndMode != null) {
            String val = strVal(sndMode, "soundMode");
            if (val != null) {
                updateState(CHANNEL_TV_SOUND_MODE, new StringType(val));
            }            // supportedSoundModes as comma-separated list
            JsonElement modesElem = attrValue(sndMode, "supportedSoundModes");
            if (modesElem != null && modesElem.isJsonArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonElement m : modesElem.getAsJsonArray()) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(m.getAsString());
                }
                if (sb.length() > 0) updateState(CHANNEL_TV_SUPPORTED_SOUND_MODES, new StringType(sb.toString()));
            }        }

        // ── playback status — mediaPlayback ───────────────────────────────────
        JsonObject playback = getCapability(root, "mediaPlayback");
        if (playback != null) {
            String val = strVal(playback, "playbackStatus");
            if (val != null) {
                updateState(CHANNEL_TV_PLAYBACK, new StringType(val));
            }
        }

        // ── watt + kwh — powerConsumptionReport ──────────────────────────────
        JsonObject pcr = getCapability(root, "powerConsumptionReport");
        if (pcr != null) {
            JsonElement pcElem = attrValue(pcr, "powerConsumption");
            if (pcElem != null && pcElem.isJsonObject()) {
                JsonObject pc = pcElem.getAsJsonObject();
                if (pc.has("power")) {
                    updateState(CHANNEL_WATT, new DecimalType(pc.get("power").getAsDouble()));
                }
                if (pc.has("energy")) {
                    // powerConsumptionReport reports energy in Wh — convert to kWh
                    updateState(CHANNEL_KWH, new DecimalType(pc.get("energy").getAsDouble() / 1000.0));
                }
            }
        }

        // ── firmware version — samsungvd.firmwareVersion ──────────────────────
        JsonObject fw = getCapability(root, "samsungvd.firmwareVersion");
        if (fw != null) {
            String val = strVal(fw, "firmwareVersion");
            if (val != null) {
                updateState(CHANNEL_TV_FIRMWARE, new StringType(val));
            }
        }

        // ── online status — samsungvd.thingStatus ─────────────────────────────
        JsonObject thingStatus = getCapability(root, "samsungvd.thingStatus");
        if (thingStatus != null) {
            String val = strVal(thingStatus, "status");
            if (val != null) {
                updateState(CHANNEL_TV_ONLINE, new StringType(val));
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private @Nullable JsonObject getCapability(JsonObject root, String capabilityId) {
        JsonElement e = root.get(capabilityId);
        return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : null;
    }

    private @Nullable String strVal(JsonObject capability, String attribute) {
        JsonElement val = attrValue(capability, attribute);
        return (val != null && !val.isJsonNull()) ? val.getAsString() : null;
    }

    private @Nullable JsonElement attrValue(JsonObject capability, String attribute) {
        JsonElement attr = capability.get(attribute);
        if (attr == null || !attr.isJsonObject())
            return null;
        return attr.getAsJsonObject().get("value");
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
