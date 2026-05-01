/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.smartthingscloud.internal.dto;

import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * DTOs for the SmartThings device component status response.
 *
 * <p>
 * Endpoint: GET /v1/devices/{deviceId}/components/main/status
 *
 * <p>
 * Shape (simplified):
 * 
 * <pre>
 * {
 *   "washerOperatingState": {
 *     "machineState":    { "value": "run",      "timestamp": "..." },
 *     "washerJobState":  { "value": "washing",  "timestamp": "..." },
 *     "completionTime":  { "value": "2026-...", "timestamp": "..." }
 *   },
 *   "switch": {
 *     "switch": { "value": "on" }
 *   },
 *   "remoteControlStatus": {
 *     "remoteControlEnabled": { "value": "true" }
 *   },
 *   "powerConsumptionReport": {
 *     "powerConsumption": { "value": { "power": 1200, "energy": 5.3 } }
 *   },
 *   "samsungce.washerWashCourse": {
 *     "washerWashCourse": { "value": "normal" }
 *   },
 *   "samsungce.washerRinseCycles": {
 *     "washerRinseCycles": { "value": "normal" }
 *   },
 *   "samsungce.washerSpinLevel": {
 *     "washerSpinLevel": { "value": "high" }
 *   },
 *   "samsungce.washerWaterTemperature": {
 *     "washerWaterTemperature": { "value": "40" }
 *   }
 * }
 * </pre>
 */
@NonNullByDefault
public class STComponentStatus {

    /** Holds all capability maps, keyed by capability name. */
    public Map<String, Map<String, STAttribute>> capabilities = Map.of();

    /**
     * A single attribute value container from the SmartThings API.
     */
    @NonNullByDefault
    public static class STAttribute {
        /** The attribute value — may be String, Number, Boolean, or a nested Map. */
        public @Nullable Object value;
        public @Nullable String timestamp;
        public @Nullable String unit;
    }

    /**
     * Power consumption value structure inside powerConsumptionReport.
     */
    @NonNullByDefault
    public static class PowerConsumption {
        /** Current power draw in Watts. */
        public double power;
        /** Energy used in Wh (accumulated). */
        public double energy;
        public double deltaEnergy;
    }
}
