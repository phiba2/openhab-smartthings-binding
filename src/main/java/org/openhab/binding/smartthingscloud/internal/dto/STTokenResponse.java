/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.smartthingscloud.internal.dto;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * DTOs for the SmartThings OAuth2 token endpoint response.
 *
 * <pre>
 * {
 *   "access_token": "...",
 *   "refresh_token": "...",
 *   "token_type": "Bearer",
 *   "expires_in": 86399
 * }
 * </pre>
 */
@NonNullByDefault
public class STTokenResponse {

    public @Nullable String access_token;
    public @Nullable String refresh_token;
    public @Nullable String token_type;
    public int expires_in;
    public @Nullable String error;
    public @Nullable String error_description;
}
