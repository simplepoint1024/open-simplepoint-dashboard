/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.cloud.oauth.server.event;

import java.time.Instant;

/**
 * Login success event.
 */
public record LoginAuthenticationSuccessEvent(
    String userId,
    String username,
    String displayName,
    String tenantId,
    String contextId,
    String sessionId,
    String remoteAddress,
    String forwardedFor,
    String userAgent,
    String requestUri,
    String authenticationType,
    Instant loginAt
) {
}
