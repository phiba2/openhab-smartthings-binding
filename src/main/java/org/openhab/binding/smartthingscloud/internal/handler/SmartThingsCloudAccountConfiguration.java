/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.smartthingscloud.internal.handler;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Configuration for the SmartThings Cloud account bridge.
 *
 * @author openHAB Samsung Cloud Binding - Initial contribution
 */
@NonNullByDefault
public class SmartThingsCloudAccountConfiguration {

    /**
     * OAuth2 client ID.
     * Defaults to the registered openHAB SmartThings app client ID.
     */
    public String clientId = "b3c72013-eddd-4f65-a33b-0d90cf271e24";

    /**
     * OAuth2 client secret. Only needed if you registered your own SmartThings app.
     * Leave empty for the default PKCE flow (recommended).
     */
    public String clientSecret = "";

    /**
     * OAuth2 redirect URI sent to SmartThings. Leave empty to use the default
     * localhost callback. Set only if you registered your own redirect URI.
     */
    public String redirectUri = "";

    /**
     * Pre-configured access token. If set together with refreshToken, the binding
     * will use these directly without requiring a browser-based login.
     * Leave empty to authorize via the /smartthingscloud web page.
     */
    public @Nullable String accessToken;

    /**
     * Pre-configured refresh token. See accessToken.
     */
    public @Nullable String refreshToken;
}
