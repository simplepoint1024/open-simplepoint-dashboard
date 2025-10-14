/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.cloud.oauth.server.expansion.oidc;

import java.util.function.Function;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcUserInfoAuthenticationContext;

/**
 * Interface for expanding OIDC user info authentication.
 * 用于扩展 OIDC 用户信息认证的接口
 */
public interface OidcUserInfoAuthenticationExpansion extends
    Function<OidcUserInfoAuthenticationContext, OidcUserInfo> {
}
