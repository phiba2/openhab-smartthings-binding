/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.smartthingscloud.internal.handler;

import static org.openhab.binding.smartthingscloud.SmartThingsCloudBindingConstants.*;

import java.time.Instant;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.smartthingscloud.internal.servlet.SmartThingsCloudServlet;
import org.openhab.core.library.types.StringType;
import org.openhab.core.storage.Storage;
import org.openhab.core.storage.StorageService;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridge handler for a SmartThings Cloud account.
 *
 * <p>
 * Manages the OAuth2 PKCE token lifecycle. Supports two init modes:
 * <ol>
 * <li><b>Pre-configured tokens</b> — accessToken/refreshToken in .things file</li>
 * <li><b>Servlet-based login</b> — user clicks "Authorize" at /smartthingscloud</li>
 * </ol>
 *
 * Tokens are persisted via {@link StorageService} so they survive reboots
 * even when the bridge is defined in a text .things file.
 *
 * @author openHAB Samsung Cloud Binding - Initial contribution
 */
@NonNullByDefault
public class SmartThingsCloudAccountHandler extends BaseBridgeHandler
        implements SmartThingsCloudApiClient.TokenRefreshCallback {

    private final Logger logger = LoggerFactory.getLogger(SmartThingsCloudAccountHandler.class);

    private final SmartThingsCloudServlet servlet;
    private final Storage<String> tokenStorage;

    private @Nullable SmartThingsCloudApiClient apiClient;

    public SmartThingsCloudAccountHandler(Bridge bridge, SmartThingsCloudServlet servlet,
            StorageService storageService) {
        super(bridge);
        this.servlet = servlet;
        this.tokenStorage = storageService.getStorage("smartthingscloud.tokens." + bridge.getUID().getAsString(),
                String.class.getClassLoader());
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void initialize() {
        logger.debug("Initializing SmartThings Cloud account bridge");
        servlet.addBridgeHandler(this);

        SmartThingsCloudAccountConfiguration config = getConfigAs(SmartThingsCloudAccountConfiguration.class);
        String clientId = config.clientId;

        if (clientId.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Client ID is not configured");
            return;
        }

        // Prefer persisted tokens (survive reboot) over .things file values
        String accessToken = coalesce(tokenStorage.get(STORAGE_ACCESS_TOKEN), config.accessToken);
        String refreshToken = coalesce(tokenStorage.get(STORAGE_REFRESH_TOKEN), config.refreshToken);

        // Restore expiry so we don't burn a refresh call if the token is still valid
        Instant expiresAt = Instant.EPOCH;
        String storedExpiry = tokenStorage.get(STORAGE_EXPIRES_AT);
        if (storedExpiry != null && !storedExpiry.isBlank()) {
            try {
                expiresAt = Instant.ofEpochSecond(Long.parseLong(storedExpiry));
                logger.debug("Restored token expiry: {} ({}s remaining)", expiresAt,
                        expiresAt.getEpochSecond() - Instant.now().getEpochSecond());
            } catch (NumberFormatException e) {
                logger.warn("Could not parse stored expiresAt '{}', will refresh on first use", storedExpiry);
            }
        }

        if (accessToken == null || refreshToken == null || accessToken.isBlank() || refreshToken.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING,
                    "Please authorize your Samsung account at http(s)://[YOUR_OPENHAB]/smartthingscloud");
            updateState("authStatus",
                    new StringType("Not authorized — open http(s)://[YOUR_OPENHAB]/smartthingscloud"));
            return;
        }

        initWithTokens(config.clientId, accessToken, refreshToken, expiresAt);
    }

    @Override
    public void dispose() {
        servlet.removeBridgeHandler(this);
        SmartThingsCloudApiClient client = this.apiClient;
        if (client != null) {
            client.dispose();
            this.apiClient = null;
        }
        super.dispose();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType && "authStatus".equals(channelUID.getId())) {
            SmartThingsCloudApiClient client = this.apiClient;
            updateState("authStatus", new StringType(client != null ? "Authorized \u2713"
                    : "Not authorized — open http(s)://[YOUR_OPENHAB]/smartthingscloud"));
        }
    }

    // ── Servlet callbacks ─────────────────────────────────────────────────────

    /**
     * Called by the servlet after a successful PKCE authorization. Persists tokens
     * and reinitializes the API client.
     */
    public void handleAuthorizationComplete(String accessToken, String refreshToken) {
        logger.info("Received new OAuth2 tokens via PKCE callback — reinitializing bridge");
        // Fresh login: token just issued, give it full 24h
        Instant expiresAt = Instant.now().plusSeconds(86399);
        persistTokens(accessToken, refreshToken, expiresAt);

        SmartThingsCloudApiClient old = this.apiClient;
        if (old != null) {
            old.dispose();
            this.apiClient = null;
        }

        SmartThingsCloudAccountConfiguration config = getConfigAs(SmartThingsCloudAccountConfiguration.class);
        initWithTokens(config.clientId, accessToken, refreshToken, expiresAt);
    }

    // ── TokenRefreshCallback ──────────────────────────────────────────────────

    @Override
    public void onTokenRefreshed(String newAccessToken, String newRefreshToken, Instant expiresAt) {
        logger.debug("Persisting auto-refreshed Samsung tokens (expires {})", expiresAt);
        persistTokens(newAccessToken, newRefreshToken, expiresAt);
    }

    // ── Accessors for child handlers ──────────────────────────────────────────

    public @Nullable SmartThingsCloudApiClient getApiClient() {
        return apiClient;
    }

    /** The client_id configured on this bridge. */
    public String getClientId() {
        return getConfigAs(SmartThingsCloudAccountConfiguration.class).clientId;
    }

    /** The OAuth2 redirect URI configured on this bridge. */
    public String getRedirectUri() {
        return getConfigAs(SmartThingsCloudAccountConfiguration.class).redirectUri;
    }

    /** The OAuth2 client secret configured on this bridge. */
    public String getClientSecret() {
        return getConfigAs(SmartThingsCloudAccountConfiguration.class).clientSecret;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void initWithTokens(String clientId, String accessToken, String refreshToken, Instant expiresAt) {
        SmartThingsCloudApiClient client = new SmartThingsCloudApiClient(clientId, accessToken, refreshToken,
                expiresAt);
        client.setTokenRefreshCallback(this);
        String secret = getClientSecret();
        if (!secret.isBlank()) {
            client.setClientSecret(secret);
        }
        this.apiClient = client;

        // Verify connectivity off the calling thread
        scheduler.execute(() -> {
            String result = client.listDevices();
            if (result != null) {
                logger.info("SmartThings Cloud account connected");
                updateStatus(ThingStatus.ONLINE);
                updateState("authStatus", new StringType("Authorized \u2713"));
            } else {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Could not connect to SmartThings API — check logs");
                updateState("authStatus", new StringType("Connection failed — check logs"));
            }
        });
    }

    private void persistTokens(String accessToken, String refreshToken, Instant expiresAt) {
        tokenStorage.put(STORAGE_ACCESS_TOKEN, accessToken);
        tokenStorage.put(STORAGE_REFRESH_TOKEN, refreshToken);
        tokenStorage.put(STORAGE_EXPIRES_AT, String.valueOf(expiresAt.getEpochSecond()));
    }

    private static @Nullable String coalesce(@Nullable String a, @Nullable String b) {
        if (a != null && !a.isBlank())
            return a;
        return b;
    }
}
