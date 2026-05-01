/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.smartthingscloud.internal.handler;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Configuration for a SmartThings Cloud television thing.
 *
 * @author openHAB Samsung Cloud Binding - Initial contribution
 */
@NonNullByDefault
public class SmartThingsCloudTelevisionConfiguration {

    /**
     * SmartThings device ID (UUID).
     */
    public String deviceId = "";

    /**
     * Polling interval in seconds for fetching TV status from SmartThings API.
     */
    public int pollingIntervalSeconds = 30;
}
