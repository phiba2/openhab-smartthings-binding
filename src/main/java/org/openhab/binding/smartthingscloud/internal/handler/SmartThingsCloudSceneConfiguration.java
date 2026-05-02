/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.smartthingscloud.internal.handler;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Configuration for a SmartThings scene thing.
 *
 * @author openHAB Samsung Cloud Binding - Initial contribution
 */
@NonNullByDefault
public class SmartThingsCloudSceneConfiguration {

    /** SmartThings scene UUID (required). */
    public String sceneId = "";

    /**
     * SmartThings location ID (optional).
     * Required by the API if the account has multiple locations.
     * Leave empty to omit the parameter (works when the account has a single location).
     */
    public String locationId = "";
}
